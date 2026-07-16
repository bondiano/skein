(ns skein-example.core
  "Демо-мод Skein: entrypoint, registry DSL, events, mixin через
  Java-стаб-паттерн (DESIGN.md §9.1). Живёт как integration-тест в CI.")

(defn init
  "Fabric `main` entrypoint (см. fabric.mod.json)."
  []
  (println "[skein-example] Hello from Clojure!"))
