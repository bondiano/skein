(ns skein-hooks-fixture.core
  "Fixture namespace for SkeinHooks unit tests. Loaded from source in
  tests; real mods are AOT-compiled — resolution is identical either way.")

(defn echo
  "Returns its arguments — exercises argument passing through the hook."
  [& args]
  (vec args))

(defn tick-handler
  "Redefined by redefine! to exercise per-call var deref (hot reload)."
  [counter]
  (swap! counter inc)
  :original)

(defn redefine! []
  (alter-var-root #'tick-handler
                  (constantly (fn [counter]
                                (swap! counter #(+ % 100))
                                :redefined))))
