(ns skein-example-test.repl-check
  "Проверки dev-REPL для integration-теста (ReplIntegrationTest): сервер
  стартует, клиент подключается по nREPL-протоколу — как CIDER/Calva —
  и всё проверяется через настоящие eval-сообщения."
  (:require [nrepl.core :as nrepl]
            [skein.repl :as repl])
  (:import (java.util.concurrent Executors ThreadFactory)))

(defn- eval-values
  "Все :value ответов на eval (строки, как их печатает nREPL)."
  [client code]
  (into [] (keep :value) (nrepl/message client {:op "eval" :code code})))

(defn- with-repl-client [opts f]
  (repl/start! opts)
  (try
    (with-open [conn (nrepl/connect :port (repl/port))]
      (f (nrepl/client conn 120000)))
    (finally
      (repl/stop!))))

(defn run-checks
  "Основной сценарий: арифметика, interop, re-def var, add-lib из Clojars."
  []
  (with-repl-client {:port 0}
    (fn [client]
      {:arith (eval-values client "(+ 1 2)")
       :interop (eval-values client "(.toUpperCase \"skein\")")
       :loader-interop (eval-values client
                                    (str "(.getId (.getMetadata (.get (.getModContainer"
                                         " (net.fabricmc.loader.api.FabricLoader/getInstance) \"skein\"))))"))
       :redef (do (eval-values client "(defn probe [] :original)")
                  (eval-values client "(defn probe [] :redefined)")
                  (eval-values client "(probe)"))
       :add-lib (do (eval-values client
                                 "(skein.repl/add-lib! 'org.clojure/data.json \"2.5.0\")")
                    (eval-values client
                                 "(do (require '[clojure.data.json :as json]) (json/write-str {:a 1}))"))})))

(defn run-game-thread-check
  "Opt-in middleware: eval уезжает на «игровой» тред (в тесте — подменённый
  Executor с узнаваемым именем треда)."
  []
  (let [executor (Executors/newSingleThreadExecutor
                  (reify ThreadFactory
                    (newThread [_ r] (doto (Thread. r "fake-game-thread")
                                       (.setDaemon true)))))]
    (reset! repl/dispatch-executor executor)
    (try
      (with-repl-client {:port 0 :game-thread-eval? true}
        (fn [client]
          {:eval-thread (eval-values client "(.getName (Thread/currentThread))")}))
      (finally
        (reset! repl/dispatch-executor nil)
        (.shutdown executor)))))
