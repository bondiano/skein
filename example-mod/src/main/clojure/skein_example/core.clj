(ns skein-example.core
  "Демо-мод Skein: entrypoint, registry DSL, events, mixin через
  Java-стаб-паттерн. Живёт как integration-тест в CI.")

(def initialized?
  "Флаг для integration-теста: true после вызова entrypoint'а loader'ом."
  (atom false))

(defn init
  "Fabric `main` entrypoint (см. fabric.mod.json)."
  []
  (reset! initialized? true)
  (println "[skein-example] Hello from Clojure!"))
