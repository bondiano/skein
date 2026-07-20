(ns skein.schema
  "Boundary validation for the FP layer — the one place data is checked against
  a Malli schema before it turns into a game object.

  Every data format the layer accepts (an item map, a blockstate map, a
  `register!` declaration, a command tree, an event payload, a text form) has a
  schema in its own namespace; this namespace turns a schema mismatch into an
  actionable error that names the path, what was expected and the offending
  fragment of the input — `[:components :enchantments] — should be a map of
  id -> level, got [:minecraft/sharpness 5] (a vector)` rather than a bare
  \"invalid map\" or a downstream ClassCastException.

  Two policies, so validation never taxes a hot path:
  - declaration boundaries (`register!`, `command/def!`, ...) run once at init
    and always validate — `validate!`;
  - coercion boundaries (`->stack`, `->component`, ...) run in tick handlers and
    stay fast — `guarded` builds the object directly and only reaches for the
    schema to explain a failure, unless `*validate*` forces an eager check (the
    adapter binds it true in dev, false in production)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def ^:dynamic *validate*
  "When true, coercion boundaries eagerly validate their input against the
  schema before building a game object, trading a little speed for a precise
  error. The adapter binds it true in the dev environment and false in
  production; declaration boundaries validate regardless of this flag."
  false)

;;; Describing the offending value

(defn- kind-of
  "A short English article + noun for a value's shape, for the tail of a
  message (`got 42 (a number)`)."
  [v]
  (cond
    (nil? v) "nil"
    (map? v) "a map"
    (vector? v) "a vector"
    (set? v) "a set"
    (sequential? v) "a sequence"
    (keyword? v) "a keyword"
    (string? v) "a string"
    (symbol? v) "a symbol"
    (boolean? v) "a boolean"
    (integer? v) "an integer"
    (number? v) "a number"
    :else (str "a " (.getSimpleName (class v)))))

(defn- fragment
  "A short, printable rendering of the offending value for the message: the
  value as data, truncated so a big map or vector does not flood the error."
  [v]
  (let [s (pr-str v)]
    (if (> (count s) 120)
      (str (subs s 0 117) "...")
      s)))

(defn- path->str
  "The input path of an error (`[:components :enchantments]`) as a readable
  location, or \"the value\" for the root."
  [in]
  (if (seq in)
    (pr-str (vec in))
    "the value"))

(defn errors
  "The schema errors for `value` against `schema` as a vector of maps
  `{:at <path-vector> :message <expectation> :value <fragment>}`, most useful
  for the REPL and for building help. Empty when the value is valid."
  [schema value]
  (when-some [explanation (m/explain schema value)]
    (mapv (fn [err]
            {:at (vec (:in err))
             :message (or (me/error-message err) "is invalid")
             :value (:value err)})
          (:errors explanation))))

(defn explain-str
  "A human explanation of why `value` does not match `schema`: one indented
  line per error naming the path, the expectation and the offending fragment.
  Duplicate lines (an `:or` reporting the same failure per branch) are
  collapsed. nil when the value is valid."
  [schema value]
  (when-some [errs (seq (errors schema value))]
    (->> errs
         (map (fn [{:keys [at message value]}]
                (str "  " (path->str at) " — " message
                     ", got " (fragment value) " (" (kind-of value) ")")))
         distinct
         (str/join "\n"))))

;;; Boundary entry points

(defn validate!
  "Throws an actionable ex-info when `value` does not match `schema`; returns
  `value` unchanged otherwise. `subject` names what is being validated, e.g.
  \"item\" -> \"Invalid item:\". Use at declaration boundaries, which run once."
  [schema value subject]
  (if-some [explanation (explain-str schema value)]
    (throw (ex-info (str "Invalid " subject ":\n" explanation)
                    {:skein/schema-error true
                     :subject subject
                     :value value
                     :errors (errors schema value)}))
    value))

(defn guarded
  "Run `build` (a thunk that turns `value` into a game object) on the fast path.
  If `*validate*` is set, validate `value` against `schema` first. If `build`
  throws, prefer a schema explanation of `value` — with the original throwable
  as the cause — when the schema can pin the problem to a shape mismatch;
  otherwise re-throw the original (a semantic error the schema cannot see, such
  as an unregistered id, keeps its own message). `subject` names the value."
  [schema value subject build]
  (when *validate*
    (validate! schema value subject))
  (try
    (build)
    (catch Throwable e
      ;; An ex-info thrown by the build already carries an actionable message
      ;; (unknown id, bad property value) — keep it. Otherwise, if the schema
      ;; can pin the failure to a shape mismatch, raise that with the original
      ;; as the cause; a cause the schema cannot see keeps its own message.
      (let [already-actionable? (and (instance? clojure.lang.ExceptionInfo e)
                                     (not (:skein/schema-error (ex-data e))))
            explanation (when-not already-actionable? (explain-str schema value))]
        (if explanation
          (throw (ex-info (str "Invalid " subject ":\n" explanation)
                          {:skein/schema-error true :subject subject :value value
                           :errors (errors schema value)}
                          e))
          (throw e))))))
