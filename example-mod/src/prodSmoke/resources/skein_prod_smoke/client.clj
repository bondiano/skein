(ns skein-prod-smoke.client
  "The nREPL client of the production smoke test (ProdReplSmokeTest):
  connects to the opt-in prod REPL of a running dedicated server and
  verifies through real eval messages that interop with game classes,
  dispatch onto the server thread and hotfixing via re-def work in
  production the same way they do in dev."
  (:require [nrepl.core :as nrepl]))

(defn- eval-values
  "All the :value replies of an eval (strings, as nREPL prints them)."
  [client code]
  (into [] (keep :value) (nrepl/message client {:op "eval" :code code})))

(defn- eval-errors
  "All the :err replies of an eval — for checks where the error is the
  expectation."
  [client code]
  (into [] (keep :err) (nrepl/message client {:op "eval" :code code})))

(defn run-checks
  "Runs the checks against the prod REPL on the given port and returns a
  map of results; the asserts live on the JUnit side."
  [port]
  (with-open [conn (nrepl/connect :port port)]
    (let [client (nrepl/client conn 120000)]
      {:arith (eval-values client "(+ 1 2)")
       :interop (eval-values client "(.toUpperCase \"skein\")")
       ;; Interop with the real server classes (Mojang names in 26.x, no
       ;; remapping): the 13 comes from max-players in the test's
       ;; server.properties.
       :max-players (eval-values client
                                 (str "(.getMaxPlayers (.getPlayerList"
                                      " (.getGameInstance (net.fabricmc.loader.api.FabricLoader/getInstance))))"))
       ;; on-game dispatches the eval body onto the real \"Server thread\".
       :server-thread (eval-values client
                                   "(do (require 'skein.repl) (skein.repl/on-game (.getName (Thread/currentThread))))")
       ;; The hotfix scenario: re-def of a var in a live prod session.
       :redef (do (eval-values client "(defn hotfix [] :v1)")
                  (eval-values client "(defn hotfix [] :v2)")
                  (eval-values client "(hotfix)"))
       ;; add-lib! must refuse in production (mods are self-contained).
       :add-lib-blocked (eval-errors client
                                     "(skein.repl/add-lib! 'org.clojure/data.json \"2.5.0\")")

       ;; --- M4: content and hot reload on a live production server ---

       ;; The registry DSL: the block and the item are really in the
       ;; game's registries.
       :registry (eval-values client
                              (str "[(.containsKey net.minecraft.core.registries.BuiltInRegistries/ITEM"
                                   " (net.minecraft.resources.Identifier/parse \"skein_example:ruby\"))"
                                   " (.containsKey net.minecraft.core.registries.BuiltInRegistries/BLOCK"
                                   " (net.minecraft.resources.Identifier/parse \"skein_example:ruby_block\"))]"))
       ;; The event wrappers: the registrations survived the production
       ;; boot.
       :event-handlers (eval-values client
                                    "(do (require 'skein.events) (mapv first (skein.events/handlers)))")
       ;; The mixin (defmixin → generated class → SkeinHooks → var).
       ;; First the structural proof — Sponge merged the generated
       ;; handler into the live server class...
       :mixin-merged (eval-values client
                                  (str "(mapv #(.getName %) (filter #(.contains (.getName %) \"skein$tickServer\")"
                                       " (.getDeclaredMethods net.minecraft.server.MinecraftServer)))"))
       ;; ...then the behavioural one: ticking on the live server. The
       ;; eval can land within the very first ticks after 'Done' — poll
       ;; briefly instead of racing the game loop.
       :mixin-ticks (eval-values client
                                 (str "(do (require 'skein-example.mixin)"
                                      " (loop [tries 0]"
                                      " (cond (pos? @skein-example.mixin/tick-count) true"
                                      " (< tries 100) (do (Thread/sleep 100) (recur (inc tries)))"
                                      " :else @skein-example.mixin/tick-count)))"))
       ;; Hot reload of mixin logic: re-def the handler from the REPL —
       ;; the very next server tick runs the new body (the counter jumps
       ;; by +1000000).
       :mixin-redef (do (eval-values client
                                     (str "(alter-var-root #'skein-example.mixin/server-mixin-tickServer"
                                          " (constantly (fn [_server _have-time _ci]"
                                          " (swap! skein-example.mixin/tick-count #(+ % 1000000)))))"))
                        (eval-values client "(do (Thread/sleep 500) (< 1000000 @skein-example.mixin/tick-count))"))
       ;; The core-lib interop helpers, baked into the mod jar.
       :players (eval-values client
                             "(do (require 'skein.interop) (skein.interop/players))")})))
