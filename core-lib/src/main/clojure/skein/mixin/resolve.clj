(ns skein.mixin.resolve
  "Build-time resolution of mixin targets against the real game classes.

  Authors write class and method names as plain symbols (MC 26.x ships
  Mojang's names, identical in dev and production); this namespace turns
  them into the internal descriptor IR that the codegen consumes:

      {:target \"net.minecraft.server.MinecraftServer\"
       :class  \"mymod.skein_mixins.ServerMixin\"
       :injects [{:type :inject
                  :method {:name \"tickServer\"
                           :desc \"(Ljava/util/function/BooleanSupplier;)V\"
                           :static false}
                  :at {:value \"HEAD\"}
                  :cancellable true
                  :handler \"mymod.core/on-server-tick\"}]}

  A typo in a target is an error here — at compile time — with the
  candidates listed in a ready-to-paste structural form. The same
  resolver runs under `defmixin` macroexpansion (errors surface in the
  REPL and the editor) and in the build pipeline for `mixins.edn`.

  Reflection-only, no dependencies: the namespace is safe to load at
  runtime (it rides into mod jars next to skein.mixin)."
  (:require [clojure.string :as str])
  (:import (java.lang.reflect Field Method Modifier)))

;;; Errors

(defn- fail
  "Throws the build-facing error: the pipeline prints the message without
  a stack trace (the :skein/mixin-error marker)."
  ([msg data] (fail msg data nil))
  ([msg data cause]
   (throw (ex-info msg (assoc data :skein/mixin-error true) cause))))

;;; Classes and descriptors

(def ^:private primitives
  {"boolean" Boolean/TYPE "byte" Byte/TYPE "char" Character/TYPE
   "short" Short/TYPE "int" Integer/TYPE "long" Long/TYPE
   "float" Float/TYPE "double" Double/TYPE "void" Void/TYPE})

(def ^:private primitive-descriptors
  {Boolean/TYPE "Z" Byte/TYPE "B" Character/TYPE "C" Short/TYPE "S"
   Integer/TYPE "I" Long/TYPE "J" Float/TYPE "F" Double/TYPE "D"
   Void/TYPE "V"})

(defn class-named
  "The Class for a symbol/string name, without initializing it. Primitive
  names resolve to the primitive classes. Fails with a compile-classpath
  hint when the class does not exist."
  ^Class [named]
  (let [n (str named)]
    (or (primitives n)
        (try
          (Class/forName n false (clojure.lang.RT/baseLoader))
          (catch ClassNotFoundException e
            (fail (str "Mixin target class '" n "' is not on the compile classpath."
                       " In MC 26.x class names are Mojang's real names, identical in dev"
                       " and production — check the spelling (fully qualified) and that the"
                       " game is on the mod's compile classpath.")
                  {:class n} e))))))

(defn descriptor
  "JVM type descriptor of a Class (\"Ljava/lang/String;\", \"I\", ...)."
  ^String [^Class c]
  (cond
    (primitive-descriptors c) (primitive-descriptors c)
    (.isArray c) (str "[" (descriptor (.getComponentType c)))
    :else (str "L" (str/replace (.getName c) "." "/") ";")))

(defn- internal-name ^String [^Class c]
  (str/replace (.getName c) "." "/"))

(defn- method-descriptor ^String [^Method m]
  (str "(" (apply str (map descriptor (.getParameterTypes m))) ")"
       (descriptor (.getReturnType m))))

;;; Structural rendering for error messages — ready to paste back into a
;;; declaration, per the fail-fast DX rule.

(defn- method-ref [^Method m]
  (str "{:name " (.getName m)
       " :params [" (str/join " " (map #(.getName ^Class %) (.getParameterTypes m))) "]}"))

(defn- overload-listing [methods]
  (str/join "\n" (map #(str "  " (method-ref %)) (sort-by method-descriptor methods))))

;;; Method specs — the three authored forms of §"method reference":
;;; a bare symbol, a structural map {:name .. :params [..]} (vector
;;; shortcut [name & params]), or a raw "name(desc)" string escape hatch.

(defn- normalize-method-spec [spec]
  (cond
    (symbol? spec) {:name (str spec)}
    (map? spec) {:name (str (:name spec)) :params (:params spec)}
    (vector? spec) {:name (str (first spec)) :params (vec (rest spec))}
    (string? spec) (if-let [paren (str/index-of spec "(")]
                     {:name (subs spec 0 paren) :desc (subs spec paren)}
                     {:name spec})
    :else (fail (str "Unsupported method reference " (pr-str spec)
                     " — use a symbol (tickServer), a map {:name tickServer :params [java.util.function.BooleanSupplier]},"
                     " a vector [tickServer java.util.function.BooleanSupplier], or a raw \"name(descriptor)\" string.")
                {:method spec})))

(defn- hint-matches?
  "A defmixin arg type hint against a parameter class: either the fully
  qualified or the simple class name; nil (no hint) matches anything."
  [hint ^Class param]
  (or (nil? hint)
      (= (str hint) (.getName param))
      (= (str hint) (.getSimpleName param))))

(defn resolve-method
  "Resolves a method spec against the target class into
  {:name :desc :static :param-descs :return-desc :param-classes}.

  opts:
  - :fits? — extra candidate predicate (Method -> boolean), used by
    defmixin to match the handler's arg vector against overloads;
  - :fits-description — one line describing what :fits? wanted, for the
    no-match error;
  - :context — where the reference comes from, prepended to errors."
  [^Class target spec {:keys [fits? fits-description context]}]
  (let [{:keys [name params desc]} (normalize-method-spec spec)
        here (or context (str "Mixin into " (.getName target)))
        ;; Bridge/synthetic methods (covariant-return bridges and the
        ;; like) are compiler artifacts, not injectable targets — they
        ;; would only show up as phantom duplicate overloads.
        named (into []
                    (comp (remove #(.isSynthetic ^Method %))
                          (filter #(= name (.getName ^Method %))))
                    (.getDeclaredMethods target))
        _ (when (empty? named)
            (fail (str here ": no method named '" name "' in " (.getName target) "."
                       " Methods declared there: "
                       (str/join ", " (->> (.getDeclaredMethods target)
                                           (remove #(.isSynthetic ^Method %))
                                           (map #(.getName ^Method %))
                                           distinct
                                           sort))
                       ". (Inherited methods cannot be mixin targets — target the declaring class.)")
                  {:target (.getName target) :method name}))
        explicit (cond
                   desc (filter #(= desc (method-descriptor %)) named)
                   params (let [wanted (mapv class-named params)]
                            (filter #(= wanted (vec (.getParameterTypes ^Method %))) named))
                   :else named)
        _ (when (empty? explicit)
            (fail (str here ": no overload of '" name "' matches "
                       (or desc (pr-str params)) ". The overloads are:\n"
                       (overload-listing named))
                  {:target (.getName target) :method name}))
        fitting (if fits? (filter fits? explicit) explicit)]
    (cond
      (empty? fitting)
      (fail (str here ": none of the '" name "' overloads fits the handler ("
                 (or fits-description "see the declaration") "). The overloads are:\n"
                 (overload-listing explicit))
            {:target (.getName target) :method name})

      (next fitting)
      (fail (str here ": method '" name "' is ambiguous — " (count fitting) " overloads:\n"
                 (overload-listing fitting)
                 "\nPick one by adding :params (paste a line from above) or, in defmixin,"
                 " type hints on the handler args.")
            {:target (.getName target) :method name})

      :else
      (let [^Method m (first fitting)]
        {:name (.getName m)
         :desc (method-descriptor m)
         :static (Modifier/isStatic (.getModifiers m))
         :param-descs (mapv descriptor (.getParameterTypes m))
         :param-classes (mapv #(.getName ^Class %) (.getParameterTypes m))
         :return-desc (descriptor (.getReturnType m))}))))

;;; @At references

(defn- at-value-str [value]
  (if (keyword? value)
    (-> value clojure.core/name (str/replace "-" "_") str/upper-case)
    (str value)))

(defn- resolve-invoke-target
  "The Sponge target string for an INVOKE @At:
  Lpkg/Owner;name(Largs;)Lret; — plus the resolved method for callers
  that need its parameter types (:modify-arg)."
  [{:keys [owner name params] :as target} context]
  (when-not (and owner name)
    (fail (str context ": an :invoke @At needs a :target map {:owner <class> :name <method>}"
               " (+ optional :params), got " (pr-str target))
          {:at-target target}))
  (let [^Class owner-class (class-named owner)
        m (resolve-method owner-class
                          (cond-> {:name name} params (assoc :params params))
                          {:context (str context ": @At :invoke target")})]
    {:target-str (str "L" (str/replace (.getName owner-class) "." "/") ";" (:name m) (:desc m))
     :method m}))

(defn- resolve-field-target
  "The Sponge target string for a FIELD @At: Lpkg/Owner;name:Ldesc;"
  [{:keys [owner name] :as target} context]
  (when-not (and owner name)
    (fail (str context ": a :field @At needs a :target map {:owner <class> :name <field>}, got "
               (pr-str target))
          {:at-target target}))
  (let [^Class owner-class (class-named owner)
        ^Field f (try
                   (.getDeclaredField owner-class (str name))
                   (catch NoSuchFieldException e
                     (fail (str context ": no field named '" name "' in " (.getName owner-class)
                                ". Fields declared there: "
                                (str/join ", " (sort (map #(.getName ^Field %)
                                                          (.getDeclaredFields owner-class)))))
                           {:target (.getName owner-class) :field (str name)} e)))]
    {:target-str (str "L" (internal-name owner-class) ";" (.getName f) ":"
                      (descriptor (.getType f)))}))

(defn resolve-at
  "Resolves an authored @At form (a keyword like :head, or a map
  {:value :invoke :target {:owner .. :name ..} :ordinal n}) into
  {:at <IR map> :invoked <resolved method or nil>}."
  [at context]
  (let [{:keys [value target ordinal] :as spec} (if (keyword? at) {:value at} at)
        _ (when-not (map? spec)
            (fail (str context ": @At must be a keyword (:head :tail :return :invoke :field)"
                       " or a map with :value, got " (pr-str at))
                  {:at at}))
        value-str (at-value-str value)
        invoke? (str/starts-with? value-str "INVOKE")
        field? (= "FIELD" value-str)
        resolved (cond
                   invoke? (resolve-invoke-target target context)
                   field? (resolve-field-target target context)
                   (some? target) (fail (str context ": @At " value-str " does not take a :target"
                                             " (only :invoke and :field do)")
                                        {:at at})
                   :else nil)]
    {:at (cond-> {:value value-str}
           resolved (assoc :target (:target-str resolved))
           ordinal (assoc :ordinal ordinal))
     :invoked (:method resolved)}))

;;; Injector declarations → IR
;;;
;;; Handler contracts (the fn the generated bytecode invokes through
;;; SkeinHooks, i.e. through the var — hot-reloadable):
;;;   :inject         (fn [this? param... ci] ...)      result ignored
;;;   :modify-arg     (fn [arg] ...)                    returns the replacement
;;;   :modify-return  (fn [this? return-value] ...)     returns the replacement

(defn handler-arity
  "Expected handler argument count for an injector type."
  [type static? param-count]
  (case type
    :inject (+ param-count (if static? 0 1) 1)
    :modify-arg 1
    :modify-return (if static? 1 2)))

(defn describe-handler-args
  "Human form of the expected handler arg vector, for error messages."
  [type static? param-count]
  (case type
    :inject (str "[" (if static? "" "this ") (str/join " " (map #(str "p" (inc %)) (range param-count)))
                 (when (pos? param-count) " ") "ci]")
    :modify-arg "[arg]"
    :modify-return (if static? "[return-value]" "[this return-value]")))

(defn- argv-fits
  "A resolve-method :fits? predicate matching a defmixin handler argv
  against candidate overloads (count + type hints), nil when no argv."
  [type argv]
  (when argv
    (fn [^Method m]
      (let [static? (Modifier/isStatic (.getModifiers m))
            ptypes (vec (.getParameterTypes m))]
        (and (= (count argv) (handler-arity type static? (count ptypes)))
             (case type
               :inject (let [param-args (subvec argv (if static? 0 1) (dec (count argv)))]
                         (every? true? (map #(hint-matches? (:tag (meta %1)) %2) param-args ptypes)))
               true))))))

(defmulti ^:private resolve-injector*
  (fn [_target injector _opts] (:type injector)))

(defmethod resolve-injector* :default
  [_ {:keys [type]} _]
  (fail (str "Unknown injector :type " (pr-str type)
             " — supported: :inject, :modify-arg, :modify-return."
             " For anything more exotic use the Java mixin stub pattern (see the template).")
        {:type type}))

(defmethod resolve-injector* :inject
  [target {:keys [method at cancellable handler] :as injector} {:keys [argv context]}]
  (when (nil? at)
    (fail (str context ": an :inject needs :at (:head, :tail, :return, or a map like"
               " {:value :invoke :target {:owner .. :name ..}})")
          {:injector injector}))
  (let [m (resolve-method target method {:fits? (argv-fits :inject argv)
                                         :fits-description
                                         (when argv (str "handler args " (pr-str argv)))
                                         :context context})
        {:keys [at]} (resolve-at at context)]
    {:type :inject
     :method (select-keys m [:name :desc :static :param-descs :return-desc])
     :at at
     :cancellable (boolean cancellable)
     :handler (str handler)}))

(defmethod resolve-injector* :modify-arg
  [target {:keys [method at index ordinal handler] :as injector} {:keys [argv context]}]
  (when (nil? at)
    (fail (str context ": a :modify-arg needs an :invoke @At pointing at the call whose"
               " argument it replaces")
          {:injector injector}))
  (let [{:keys [at invoked]} (resolve-at at context)
        _ (when-not invoked
            (fail (str context ": a :modify-arg @At must be :invoke (got " (:value at) ")")
                  {:injector injector}))
        _ (when (and argv (not= 1 (count argv)))
            (fail (str context ": a :modify-arg handler takes exactly [arg] — the argument value,"
                       " returning the replacement; got " (pr-str argv))
                  {:injector injector}))
        m (resolve-method target method {:context context})
        param-descs (:param-descs invoked)
        arg-index (or index ordinal
                      (when (= 1 (count param-descs)) 0)
                      (fail (str context ": the invoked method takes " (count param-descs)
                                 " arguments — say which one to modify with :index (0-based)")
                            {:injector injector}))
        _ (when-not (< -1 arg-index (count param-descs))
            (fail (str context ": :index " arg-index " is out of range — the invoked method takes "
                       (count param-descs) " argument(s)")
                  {:injector injector}))]
    {:type :modify-arg
     :method (select-keys m [:name :desc :static :param-descs :return-desc])
     :at at
     :index arg-index
     :arg-desc (nth param-descs arg-index)
     :handler (str handler)}))

(defmethod resolve-injector* :modify-return
  [target {:keys [method at handler] :as injector} {:keys [argv context]}]
  (when at
    (fail (str context ": :modify-return has a fixed injection point (every RETURN) — drop :at,"
               " or use :inject for custom points")
          {:injector injector}))
  (let [m (resolve-method target method {:fits? (argv-fits :modify-return argv)
                                         :fits-description
                                         (when argv (str "handler args " (pr-str argv)))
                                         :context context})]
    (when (= "V" (:return-desc m))
      (fail (str context ": '" (:name m) "' returns void — there is no return value to modify;"
                 " use :inject with :at :return/:tail instead")
            {:injector injector}))
    {:type :modify-return
     :method (select-keys m [:name :desc :static :param-descs :return-desc])
     :handler (str handler)}))

(defn resolve-injector
  "Resolves one authored injector declaration against the target class
  into its IR entry. opts: :argv (defmixin handler arg vector, for
  overload matching and arity errors), :context (error prefix)."
  [^Class target injector opts]
  (let [context (or (:context opts)
                    (str "Mixin into " (.getName target)
                         (when-let [h (:handler injector)] (str " (handler " h ")"))))]
    (resolve-injector* target injector (assoc opts :context context))))

(defn resolve-declaration
  "Resolves a whole authored mixin declaration (the mixins.edn shape,
  symbols and all) into the IR. The :class must already be decided by
  the caller (defmixin derives it from the ns, the pipeline from the
  mod id)."
  [{:keys [target class injects] :as declaration}]
  (when-not target
    (fail (str "Mixin declaration has no :target: " (pr-str declaration)) {:declaration declaration}))
  (when-not class
    (fail (str "Mixin declaration has no :class: " (pr-str declaration)) {:declaration declaration}))
  (let [^Class target-class (class-named target)]
    {:target (.getName target-class)
     :class (str class)
     :injects (mapv #(resolve-injector target-class % {}) injects)}))
