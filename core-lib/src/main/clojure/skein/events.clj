(ns skein.events
  "Wrappers over Fabric API event callbacks that register a var, not a fn.

      (defn on-tick [server] ...)
      (events/on! :server/tick-end #'on-tick)

  The var itself is registered: every event fire dereferences its current
  value, so re-defing the handler from the REPL changes the behaviour of
  the live game immediately. A repeated on! with the same [event var] pair
  (re-eval of the ns) is a no-op: Fabric events cannot unregister, so
  deduplication is mandatory.

  Two handler styles coexist, pick one per handler:
  - on!      — a side-effecting handler (style A): it receives the raw event
               arguments and does its work with `!`-functions;
  - on-pure! — a pure handler (style B): it receives the event's data map and
               returns a vector of effects, which the wrapper runs on the game
               thread through skein.fx. The pure handler stays an ordinary
               data -> effects function, testable without a game.

  Requires fabric-api on the mod's classpath (the lifecycle-events,
  events-interaction and networking modules). The callback interfaces are
  resolved lazily — a missing fabric-api produces an explained error from
  on!, not at ns load.

  The v1 event catalog (handler arguments):
  - :server/starting :server/started :server/stopping :server/stopped [server]
  - :server/tick-start :server/tick-end [server]
  - :player/join [player] — the player is fully in the game
  - :player/disconnect [player]
  - :block/use [player world hand hit-result] — result is nil/:pass,
    :success, :consume, :fail or a ready-made InteractionResult
  - :block/attack [player world hand pos direction] — result as above
  - :item/use [player world hand] — result as above"
  (:require [skein.fx :as fx]
            [skein.schemas :as schemas])
  (:import (net.minecraft.world InteractionResult)))

;;; Results of interaction events

(defn- ->interaction-result
  ^InteractionResult [result event-key]
  (cond
    (nil? result) InteractionResult/PASS
    (instance? InteractionResult result) result
    :else (case result
            :pass InteractionResult/PASS
            :success InteractionResult/SUCCESS
            :consume InteractionResult/CONSUME
            :fail InteractionResult/FAIL
            (throw (ex-info (str "Handler for " event-key " returned " (pr-str result)
                                 " — expected nil, :pass, :success, :consume, :fail or an InteractionResult")
                            {:event event-key :result result})))))

;;; The event catalog: :event is a thunk of the Event instance, :listener
;;; is a callback factory over the var. The fabric-api class references
;;; are fully qualified and resolved lazily by the JVM on first execution
;;; — see the ns docstring.

(def ^:private events
  {:server/starting
   {:event (fn [] net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents/SERVER_STARTING)
    :listener (fn [v] (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarting
                        (onServerStarting [_ server] (v server))))}

   :server/started
   {:event (fn [] net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents/SERVER_STARTED)
    :listener (fn [v] (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarted
                        (onServerStarted [_ server] (v server))))}

   :server/stopping
   {:event (fn [] net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents/SERVER_STOPPING)
    :listener (fn [v] (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStopping
                        (onServerStopping [_ server] (v server))))}

   :server/stopped
   {:event (fn [] net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents/SERVER_STOPPED)
    :listener (fn [v] (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStopped
                        (onServerStopped [_ server] (v server))))}

   :server/tick-start
   {:event (fn [] net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents/START_SERVER_TICK)
    :listener (fn [v] (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents$StartTick
                        (onStartTick [_ server] (v server))))}

   :server/tick-end
   {:event (fn [] net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents/END_SERVER_TICK)
    :listener (fn [v] (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents$EndTick
                        (onEndTick [_ server] (v server))))}

   :player/join
   {:event (fn [] net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents/JOIN)
    :listener (fn [v] (reify net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Join
                        (onPlayReady [_ handler _sender _server]
                          (v (.getPlayer ^net.minecraft.server.network.ServerPlayerConnection handler)))))}

   :player/disconnect
   {:event (fn [] net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents/DISCONNECT)
    :listener (fn [v] (reify net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Disconnect
                        (onPlayDisconnect [_ handler _server]
                          (v (.getPlayer ^net.minecraft.server.network.ServerPlayerConnection handler)))))}

   :block/use
   {:event (fn [] net.fabricmc.fabric.api.event.player.UseBlockCallback/EVENT)
    :listener (fn [v] (reify net.fabricmc.fabric.api.event.player.UseBlockCallback
                        (interact [_ player world hand hit]
                          (->interaction-result (v player world hand hit) :block/use))))}

   :block/attack
   {:event (fn [] net.fabricmc.fabric.api.event.player.AttackBlockCallback/EVENT)
    :listener (fn [v] (reify net.fabricmc.fabric.api.event.player.AttackBlockCallback
                        (interact [_ player world hand pos direction]
                          (->interaction-result (v player world hand pos direction) :block/attack))))}

   :item/use
   {:event (fn [] net.fabricmc.fabric.api.event.player.UseItemCallback/EVENT)
    :listener (fn [v] (reify net.fabricmc.fabric.api.event.player.UseItemCallback
                        (interact [_ player world hand]
                          (->interaction-result (v player world hand) :item/use))))}})

;;; Pure handlers (style B): the raw event arguments a Fabric callback fires
;;; with, shaped into the event-data map a pure handler reads and the ctx the
;;; effects it returns act on. The values are the live game objects the event
;;; carries — richer coercion (an [x y z] pos, a lazy snapshot) is the domain
;;; layer's job and lands with it; the shape of the data map does not change.

(def ^:private pure-context
  {:server/starting   (fn [[s]] {:data {:server s} :ctx s})
   :server/started    (fn [[s]] {:data {:server s} :ctx s})
   :server/stopping   (fn [[s]] {:data {:server s} :ctx s})
   :server/stopped    (fn [[s]] {:data {:server s} :ctx s})
   :server/tick-start (fn [[s]] {:data {:server s} :ctx s})
   :server/tick-end   (fn [[s]] {:data {:server s} :ctx s})
   :player/join       (fn [[p]] {:data {:player p} :ctx p})
   :player/disconnect (fn [[p]] {:data {:player p} :ctx p})
   :block/use    (fn [[player world hand hit]]
                   {:data {:player player :world world :hand hand :hit hit} :ctx world})
   :block/attack (fn [[player world hand pos direction]]
                   {:data {:player player :world world :hand hand :pos pos :direction direction} :ctx world})
   :item/use     (fn [[player world hand]]
                   {:data {:player player :world world :hand hand} :ctx world})})

;;; Registration

(defonce ^:private registrations (atom #{}))

(defn- ensure-fabric-api! [event-key]
  (try
    (Class/forName "net.fabricmc.fabric.api.event.Event" false (.getClassLoader clojure.lang.RT))
    (catch ClassNotFoundException e
      (throw (ex-info (str "Cannot register " event-key ": skein.events wraps Fabric API callbacks,"
                           " but fabric-api is not on the classpath. Add it to the mod's dependencies.")
                      {:event event-key} e)))))

(defn on!
  "Registers a var as the event's handler (see the ns docstring).
  Idempotent per [event-key var] pair. Returns event-key."
  [event-key handler-var]
  (when-not (contains? events event-key)
    (throw (ex-info (str "Unknown event " event-key ". Supported: " (vec (sort (keys events))))
                    {:event event-key :supported (vec (sort (keys events)))})))
  (when-not (var? handler-var)
    (throw (ex-info (str "Handler for " event-key " must be a var (#'my-handler), got: "
                         (pr-str handler-var)
                         ". Registering the var — not the fn — is what makes the handler hot-reloadable.")
                    {:event event-key :handler handler-var})))
  (ensure-fabric-api! event-key)
  (when-not (contains? @registrations [event-key handler-var])
    (let [{:keys [event listener]} (events event-key)]
      (.register ^net.fabricmc.fabric.api.event.Event (event) (listener handler-var))
      (swap! registrations conj [event-key handler-var])))
  event-key)

(defn on-pure!
  "Registers a *pure* handler var for an event (style B): a function of the
  event's data map that returns a vector of effects (see skein.fx). The wrapper
  derefs the var on every fire (hot reload), calls it with the event's data,
  and runs the returned effects on the game thread — the callback already runs
  there, so no on-server dispatch is needed. A nil return runs nothing.

  Idempotent per [event-key var] pair; coexists with an on! handler on the same
  event. Returns event-key. The pure handler is an ordinary data -> effects
  function — test it by calling it directly, no game required."
  [event-key handler-var]
  (when-not (contains? events event-key)
    (throw (ex-info (str "Unknown event " event-key ". Supported: " (vec (sort (keys events))))
                    {:event event-key :supported (vec (sort (keys events)))})))
  (when-not (var? handler-var)
    (throw (ex-info (str "Pure handler for " event-key " must be a var (#'my-handler), got: "
                         (pr-str handler-var)
                         ". Registering the var — not the fn — is what makes the handler hot-reloadable.")
                    {:event event-key :handler handler-var})))
  (ensure-fabric-api! event-key)
  (when-not (contains? @registrations [event-key handler-var :pure])
    (let [{:keys [event listener]} (events event-key)
          build (pure-context event-key)
          wrapper (fn [& raw]
                    (let [{:keys [data ctx]} (build (vec raw))
                          effects (handler-var data)]
                      (cond
                        (nil? effects) nil
                        (sequential? effects) (fx/run-effects! ctx effects)
                        :else (throw (ex-info (str "Pure handler " handler-var " for " event-key
                                                   " must return a vector of effects or nil, got: "
                                                   (pr-str effects))
                                              {:event event-key :handler handler-var :returned effects})))
                      nil))]
      (.register ^net.fabricmc.fabric.api.event.Event (event) (listener wrapper))
      (swap! registrations conj [event-key handler-var :pure])))
  event-key)

(defn handlers
  "The current registrations: a sorted vector of [event-key var style] triples,
  where style is :fn (a side-effecting on! handler) or :pure (an on-pure!
  effect handler)."
  []
  (->> @registrations
       (map (fn [entry] (if (= 3 (count entry)) entry (conj entry :fn))))
       (sort-by (comp str first))
       vec))

(defn payload
  "The keys a pure handler (`on-pure!`) receives in its data map for an event —
  for the REPL. The values are live game objects; coerce them with the domain
  namespaces (`entity/snapshot`, `world/block-at`, ...)."
  [event-key]
  (or (schemas/payload-keys event-key)
      (throw (ex-info (str "Unknown event " event-key ". Supported: " (vec (sort (keys events))))
                      {:event event-key}))))

(defn catalog
  "A map of every event key -> the payload keys a pure handler receives, for
  discovery from the REPL."
  []
  (into (sorted-map)
        (map (fn [k] [k (schemas/payload-keys k)]))
        (keys events)))
