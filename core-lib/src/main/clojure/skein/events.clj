(ns skein.events
  "Wrappers over Fabric API event callbacks that register a var, not a fn.

      (defn on-tick [server] ...)
      (events/on! :server/tick-end #'on-tick)

  The var itself is registered: every event fire dereferences its current
  value, so re-defing the handler from the REPL changes the behaviour of
  the live game immediately. A repeated on! with the same [event var] pair
  (re-eval of the ns) is a no-op: Fabric events cannot unregister, so
  deduplication is mandatory.

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

(defn handlers
  "The current registrations: a sorted vector of [event-key var] pairs."
  []
  (vec (sort-by (comp str first) @registrations)))
