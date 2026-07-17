(ns skein-example-test.repl-test
  "Dev-REPL end to end in the headless Fabric env: the nREPL server from
  the repl module starts in this JVM, a client connects over the real
  nREPL protocol (exactly what CIDER/Calva speak) and everything is
  asserted through eval messages — including interop with loader
  classes, re-def of a var, add-lib from Clojars and the opt-in
  game-thread middleware."
  (:require [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [skein.repl :as repl])
  (:import (java.util.concurrent Executors ThreadFactory)))

(defn- eval-values
  "All the :value replies of an eval (strings, as nREPL prints them)."
  [client code]
  (into [] (keep :value) (nrepl/message client {:op "eval" :code code})))

(defmacro with-repl
  "Starts the dev nREPL server with opts, binds a connected nREPL client
  to client-sym for the body, always stops the server."
  [[client-sym opts] & body]
  `(do
     (repl/start! ~opts)
     (try
       (with-open [conn# (nrepl/connect :port (repl/port))]
         (let [~client-sym (nrepl/client conn# 120000)]
           ~@body))
       (finally
         (repl/stop!)))))

(deftest dev-repl-end-to-end
  (with-repl [client {:port 0}]
    (is (= ["3"] (eval-values client "(+ 1 2)")) "plain eval")
    (is (= ["\"SKEIN\""] (eval-values client "(.toUpperCase \"skein\")")) "java interop")
    (is (= ["\"skein\""]
           (eval-values client
                        (str "(.getId (.getMetadata (.get (.getModContainer"
                             " (net.fabricmc.loader.api.FabricLoader/getInstance) \"skein\"))))")))
        "interop with loader classes")
    (eval-values client "(defn probe [] :original)")
    (eval-values client "(defn probe [] :redefined)")
    (is (= [":redefined"] (eval-values client "(probe)"))
        "re-def of a var in a live session")
    (eval-values client "(skein.repl/add-lib! 'org.clojure/data.json \"2.5.0\")")
    (is (= ["\"{\\\"a\\\":1}\""]
           (eval-values client
                        "(do (require '[clojure.data.json :as json]) (json/write-str {:a 1}))"))
        "lib added from Clojars is usable")))

(deftest game-thread-middleware-dispatches-eval
  ;; Opt-in middleware: the eval moves to the "game" thread (in the test
  ;; — a substituted Executor with a recognizable thread name).
  (let [executor (Executors/newSingleThreadExecutor
                  (reify ThreadFactory
                    (newThread [_ r] (doto (Thread. r "fake-game-thread")
                                       (.setDaemon true)))))]
    (reset! repl/dispatch-executor executor)
    (try
      (with-repl [client {:port 0 :game-thread-eval? true}]
        (is (= ["\"fake-game-thread\""]
               (eval-values client "(.getName (Thread/currentThread))"))
            "eval runs on the substituted game thread"))
      (finally
        (reset! repl/dispatch-executor nil)
        (.shutdown executor)))))
