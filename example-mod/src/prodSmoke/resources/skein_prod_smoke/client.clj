(ns skein-prod-smoke.client
  "nREPL-клиент production smoke-теста (ProdReplSmokeTest): подключается к
  opt-in prod REPL работающего dedicated-сервера и проверяет через настоящие
  eval-сообщения, что interop с классами игры, диспатч на серверный тред и
  hotfix через re-def работают в production так же, как в dev."
  (:require [nrepl.core :as nrepl]))

(defn- eval-values
  "Все :value ответов на eval (строки, как их печатает nREPL)."
  [client code]
  (into [] (keep :value) (nrepl/message client {:op "eval" :code code})))

(defn- eval-errors
  "Все :err ответов на eval — для проверок, где ошибка и есть ожидание."
  [client code]
  (into [] (keep :err) (nrepl/message client {:op "eval" :code code})))

(defn run-checks
  "Гоняет проверки против prod REPL на данном порту, возвращает map
  результатов; assert'ы — на стороне JUnit."
  [port]
  (with-open [conn (nrepl/connect :port port)]
    (let [client (nrepl/client conn 120000)]
      {:arith (eval-values client "(+ 1 2)")
       :interop (eval-values client "(.toUpperCase \"skein\")")
       ;; Interop с реальными классами сервера (в 26.x имена Mojang, без
       ;; ремапа): 13 приезжает из max-players в server.properties теста.
       :max-players (eval-values client
                                 (str "(.getMaxPlayers (.getPlayerList"
                                      " (.getGameInstance (net.fabricmc.loader.api.FabricLoader/getInstance))))"))
       ;; on-game диспатчит eval-тело на настоящий «Server thread».
       :server-thread (eval-values client
                                   "(do (require 'skein.repl) (skein.repl/on-game (.getName (Thread/currentThread))))")
       ;; Hotfix-сценарий: re-def var в живой prod-сессии.
       :redef (do (eval-values client "(defn hotfix [] :v1)")
                  (eval-values client "(defn hotfix [] :v2)")
                  (eval-values client "(hotfix)"))
       ;; add-lib! обязан отказать в production (моды self-contained).
       :add-lib-blocked (eval-errors client
                                     "(skein.repl/add-lib! 'org.clojure/data.json \"2.5.0\")")})))
