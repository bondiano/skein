(ns skein-scripts-smoke.client
  "The nREPL client of the Skein Scripts production smoke test: connects to
  the opt-in prod REPL of a running dedicated server and verifies, through
  real eval messages, that a script loaded at the server-started phase, that
  a deps.edn library resolved in production, and that editing the script file
  and calling reload! hot-reloads it."
  (:require [nrepl.core :as nrepl]))

(defn- eval-values
  "All the :value replies of an eval (strings, as nREPL prints them)."
  [client code]
  (into [] (keep :value) (nrepl/message client {:op "eval" :code code})))

;; The scripts load on the SERVER_STARTED event, which can fire a beat after
;; the \"Done\" console line the harness waits on — poll briefly for the
;; script namespace instead of racing that ordering.
(def ^:private await-marker
  (str "(loop [n 0]"
       " (cond (find-ns 'smoke.script) (deref (resolve 'smoke.script/marker))"
       " (< n 100) (do (Thread/sleep 100) (recur (inc n)))"
       " :else :not-loaded))"))

(defn run-checks
  "Runs the checks against the prod REPL on the given port. `rewrite-script!`
  is a 0-arg thunk (called from the test JVM) that overwrites the script file
  on disk with its v2 body; it runs between the initial read and the reload so
  the reload has something new to pick up. Returns a map of results; the
  asserts live on the JUnit side."
  [port rewrite-script!]
  (with-open [conn (nrepl/connect :port port)]
    (let [client (nrepl/client conn 120000)]
      {;; The script ran at the server-started phase and defined its var.
       :marker-initial (eval-values client await-marker)

       ;; loader/status reports both scripts, both :ok. nREPL prints the
       ;; returned value itself — no pr-str here, or it would double-quote.
       :status (eval-values client
                            (str "(mapv (juxt :name :status)"
                                 " (:scripts (skein-scripts.loader/status)))"))

       ;; The deps.edn library (org.clojure/data.csv — NOT in the JiJ'd
       ;; tools.deps closure) resolved in production and the script that
       ;; requires it loaded and used it.
       :deps-status (eval-values client
                                 "(:status (:deps (skein-scripts.loader/status)))")
       :deps-parsed (eval-values client
                                 "(loop [n 0] (cond (find-ns 'smoke.deps) (deref (resolve 'smoke.deps/row)) (< n 50) (do (Thread/sleep 100) (recur (inc n))) :else \"not-loaded\"))")

       ;; Hot reload: rewrite the script on disk (test JVM), reload from the
       ;; REPL (dispatched onto the server thread), read the new value.
       :marker-reloaded (do (rewrite-script!)
                            (eval-values client "(skein-scripts.core/reload!)")
                            (eval-values client "(deref (resolve 'smoke.script/marker))"))})))
