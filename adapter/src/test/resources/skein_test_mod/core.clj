(ns skein-test-mod.core
  "Fixture namespace for ClojureLanguageAdapter unit tests. Loaded from
  source in tests; real mods are AOT-compiled — resolution is identical
  either way.")

(defn init
  "Convention entrypoint for the bare-namespace form."
  []
  :initialized)

(defn greet
  "SAM-wrapping fixture."
  [name]
  (str "Hello, " name))

(def answer
  "Deliberately not a function and not an interface instance."
  42)

(def runner
  "A reify instance — returned by the adapter as-is."
  (reify Runnable
    (run [_] nil)))

(defn flip
  "Redefined by flip! to exercise per-call var deref (hot reload)."
  []
  :original)

(defn flip! []
  (alter-var-root #'flip (constantly (fn [] :redefined))))
