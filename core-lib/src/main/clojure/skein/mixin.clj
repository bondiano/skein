(ns skein.mixin
  "Mixins as data: declarations instead of annotated Java classes.

  A mixin is described either by an EDN structure (mixins.edn in the
  mod's resources) or by the `defmixin` macro, which colocates the
  declaration with its handlers. Both feed the same build-time pipeline:
  targets are verified against the real game classes at compile time
  (a typo is a build error, not a runtime surprise), and the mixin
  classes themselves are generated as bytecode whose bodies call the
  handler *vars* — so mixin logic is hot-reloadable from the REPL, while
  the injection points are fixed at build time.

      (defmixin server-mixin
        {:target net.minecraft.server.MinecraftServer}
        (inject {:at :head :cancellable true}
          (tickServer [this have-time ci]
            (when (paused?) (cancel! ci)))))

  Each injector body becomes a plain `defn` named
  `<mixin-name>-<method>` (here: `server-mixin-tickServer`); redefine it
  and the very next call from the game runs the new code. Handler
  contracts:

  - `inject`        `[this? param... ci]` — result ignored; `ci` is the
                    CallbackInfo (CallbackInfoReturnable for a non-void
                    target);
  - `modify-arg`    `[arg]` — returns the replacement argument;
  - `modify-return` `[this? return-value]` — returns the replacement.

  (`this?`: present for instance target methods, absent for static.)

  Overloaded target methods are disambiguated with type hints on the
  handler args — the same hints the reflection perf-lint already wants.
  Resolution happens during macroexpansion, so a bad target fails in the
  editor/REPL immediately, with the overload candidates listed ready to
  paste."
  (:require [clojure.string :as str]
            [skein.mixin.resolve :as resolve])
  (:import (org.spongepowered.asm.mixin.injection.callback CallbackInfo CallbackInfoReturnable)))

;;; CallbackInfo helpers

(defn cancel!
  "Cancels a cancellable injection (the target method returns early).
  The :inject declaration must say :cancellable true."
  [^CallbackInfo ci]
  (.cancel ci)
  nil)

(defn set-return!
  "Sets the target method's return value from an :inject into a non-void
  method (the ci there is a CallbackInfoReturnable). Implies cancel.
  Prefer :modify-return when the whole point is replacing the value."
  [^CallbackInfoReturnable cir value]
  (.setReturnValue cir value)
  nil)

;;; The build-time registry: loading a namespace with defmixin forms
;;; (which AOT compilation does) collects the resolved declarations
;;; here; the build pipeline drains it after compiling the mod.
;;; Keyed by generated class name, so re-loading a namespace (REPL
;;; re-eval, repeated compile passes) stays idempotent.

(defonce ^:private registry (atom {}))

(defn register-mixin!
  "Registers a resolved mixin declaration (defmixin expansion calls
  this; not meant to be called by hand). Returns the declaration."
  [declaration]
  (swap! registry assoc (:class declaration) declaration)
  declaration)

(defn declarations
  "All mixin declarations registered so far (build pipeline API)."
  []
  (vec (vals @registry)))

;;; defmixin

(defn- macro-error [msg data]
  (ex-info msg (assoc data :skein/mixin-error true)))

(def ^:private injector-kinds '#{inject modify-arg modify-return})

(defn- parse-injector
  "One (kind opts? (method [args] body...)) form → intermediate map."
  [mixin-name form]
  (when-not (and (seq? form) (contains? injector-kinds (first form)))
    (throw (macro-error
            (str "defmixin " mixin-name ": expected an injector form (inject ...),"
                 " (modify-arg ...) or (modify-return ...), got " (pr-str form))
            {:form form})))
  (let [[kind & more] form
        [opts more] (if (map? (first more)) [(first more) (rest more)] [{} more])
        [handler-form & extra] more]
    (when (or (nil? handler-form) (seq extra))
      (throw (macro-error
              (str "defmixin " mixin-name ": (" kind " ...) takes an optional options map and"
                   " exactly one (methodName [args] body...) form — like deftype method syntax")
              {:form form})))
    (when-not (and (seq? handler-form)
                   (symbol? (first handler-form))
                   (vector? (second handler-form)))
      (throw (macro-error
              (str "defmixin " mixin-name ": the handler must be (methodName [args] body...),"
                   " got " (pr-str handler-form))
              {:form form})))
    (let [[method-sym argv & body] handler-form]
      {:kind (keyword kind)
       :opts opts
       :method method-sym
       :argv argv
       :body body
       :handler-name (or (:name opts) (symbol (str mixin-name "-" method-sym)))})))

(defn- pascal-case [s]
  (apply str (map str/capitalize (str/split (str s) #"-"))))

(defn- default-class-name
  "Generated classes live in a package of their own —
  <modid>.skein_mixins — because the mixin config's package is excluded
  from normal classloading; nothing else may live there. The mod id is
  the ns root (the ns-naming convention the plugin lints for)."
  [ns-sym mixin-name]
  (let [root (first (str/split (str ns-sym) #"\."))]
    (str (munge root) ".skein_mixins." (pascal-case mixin-name))))

(defmacro defmixin
  "Declares a mixin: target verification and overload resolution happen
  right now, at macroexpansion (with the game on the classpath); the
  mixin class itself is generated by the build pipeline. Defines one
  `defn` per injector (named `<mixin-name>-<method>`, override with
  :name in the injector options) plus `mixin-name` bound to the
  resolved declaration. See the ns docstring for syntax and handler
  contracts."
  [mixin-name opts & injectors]
  (when-not (and (map? opts) (:target opts))
    (throw (macro-error
            (str "defmixin " mixin-name ": the first form must be an options map with :target,"
                 " e.g. {:target net.minecraft.server.MinecraftServer}")
            {:opts opts})))
  (when (empty? injectors)
    (throw (macro-error (str "defmixin " mixin-name " declares no injectors") {})))
  (let [target-class (resolve/class-named (:target opts))
        class-name (str (or (:class opts) (default-class-name (ns-name *ns*) mixin-name)))
        parsed (mapv #(parse-injector mixin-name %) injectors)
        _ (when-let [[dup] (->> (map :handler-name parsed) frequencies (filter #(< 1 (val %))) first)]
            (throw (macro-error
                    (str "defmixin " mixin-name ": two injectors would both define handler '" dup
                         "' — give one of them its own name with {:name other-handler} in the"
                         " injector options")
                    {:handler dup})))
        injects (mapv (fn [{:keys [kind opts method argv handler-name]}]
                        (resolve/resolve-injector
                         target-class
                         ;; :method in the injector options is the escape
                         ;; hatch for overloads that hints cannot separate
                         ;; (a structural {:name .. :params [..]} form).
                         (cond-> (-> opts
                                     (dissoc :name)
                                     (assoc :type kind
                                            :handler (str (ns-name *ns*) "/" handler-name)))
                           (not (contains? opts :method)) (assoc :method method))
                         {:argv argv
                          :context (str "defmixin " mixin-name ", (" (name kind) " ... (" method " "
                                        (pr-str argv) " ...))")}))
                      parsed)
        declaration {:target (.getName ^Class target-class)
                     :class class-name
                     :injects injects}]
    `(do
       ~@(map (fn [{:keys [handler-name argv body]}]
                `(defn ~handler-name ~argv ~@body))
              parsed)
       (register-mixin! '~declaration)
       (def ~mixin-name '~declaration))))
