(ns skein.client.events
  "Client-side event callbacks, wrapped to register a var, not a fn — the client
  half of skein.events.

      (defn on-tick [{:keys [client]}] ...)
      (client.events/on! :client/tick-end #'on-tick)

  As with skein.events, the var itself is registered: every fire dereferences
  its current value, so redefining the handler from the REPL changes the live
  client immediately. A repeated on! with the same [event var] pair (an ns
  re-eval) is a no-op — Fabric events cannot unregister, so deduplication is
  mandatory.

  Two handler styles, same as everywhere else in the layer:
  - on!      — a side-effecting handler (style A): it receives the event's data
               map and does its work directly (drawing, input, sound);
  - on-pure! — a pure handler (style B): it receives the same map and returns a
               vector of effects, run through skein.fx against the event's ctx
               (the client, or the client level for the level-tick events). The
               built-in fx effects are server-side; client effects are the mod's
               own defmethods.

  The event's data map always carries `:side :client`, the `:client` instance,
  and — for the level-tick events — the `:level` it fired for.

  This namespace is client-only: register from the mod's client entrypoint. It
  loads only there, so its client-class references never run on a dedicated
  server; on! raises an actionable error if reached from the wrong side anyway.
  Needs fabric-api (the lifecycle-events module) on the classpath.

  The client event catalog (data-map keys):
  - :client/started :client/stopping        [:client]
  - :client/tick-start :client/tick-end     [:client] — every client tick
  - :client/level-tick-start
    :client/level-tick-end                  [:client :level] — while in a world"
  (:require [skein.fx :as fx]
            [skein.interop :as interop])
  (:import (net.minecraft.client Minecraft)
           (net.minecraft.client.multiplayer ClientLevel)))

;;; The event catalog. As in skein.events, the fabric-api class references are
;;; fully qualified and resolved lazily by the JVM on first execution — a
;;; missing fabric-api surfaces as an explained error from on!, not at ns load.
;;; :event is a thunk of the Event instance; :listener wraps a callback fn.

(def ^:private events
  {:client/started
   {:event (fn [] net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents/CLIENT_STARTED)
    :listener (fn [f] (reify net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents$ClientStarted
                        (onClientStarted [_ client] (f client))))}

   :client/stopping
   {:event (fn [] net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents/CLIENT_STOPPING)
    :listener (fn [f] (reify net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents$ClientStopping
                        (onClientStopping [_ client] (f client))))}

   :client/tick-start
   {:event (fn [] net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents/START_CLIENT_TICK)
    :listener (fn [f] (reify net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$StartTick
                        (onStartTick [_ client] (f client))))}

   :client/tick-end
   {:event (fn [] net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents/END_CLIENT_TICK)
    :listener (fn [f] (reify net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndTick
                        (onEndTick [_ client] (f client))))}

   :client/level-tick-start
   {:event (fn [] net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents/START_LEVEL_TICK)
    :listener (fn [f] (reify net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$StartLevelTick
                        (onStartTick [_ level] (f level))))}

   :client/level-tick-end
   {:event (fn [] net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents/END_LEVEL_TICK)
    :listener (fn [f] (reify net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndLevelTick
                        (onEndTick [_ level] (f level))))}})

;;; The data map a handler reads, and the ctx effects run against. The lifecycle
;;; and client-tick events fire with the Minecraft instance; the level-tick
;;; events fire with the ClientLevel (its client is reachable through it).

(defn- client-data [^Minecraft client]
  {:side :client :client client})

(defn- level-data [^ClientLevel level]
  {:side :client :client (Minecraft/getInstance) :level level})

(def ^:private contexts
  ;; event -> [build-data-map ctx-for-effects]. ctx is what fx/run-effects! runs
  ;; against: the client for a client event, the level for a level-tick event.
  {:client/started         [client-data (fn [c] c)]
   :client/stopping        [client-data (fn [c] c)]
   :client/tick-start      [client-data (fn [c] c)]
   :client/tick-end        [client-data (fn [c] c)]
   :client/level-tick-start [level-data (fn [l] l)]
   :client/level-tick-end   [level-data (fn [l] l)]})

;;; Registration

(defonce ^:private registrations (atom #{}))

(defn- ensure-fabric-api! [event-key]
  (try
    (Class/forName "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents"
                   false (.getClassLoader clojure.lang.RT))
    (catch ClassNotFoundException e
      (throw (ex-info (str "Cannot register " event-key ": skein.client.events wraps Fabric API"
                           " client callbacks, but fabric-api is not on the classpath. Add it to the"
                           " mod's dependencies.")
                      {:event event-key} e)))))

(defn- check-event! [event-key handler-var what]
  (when-not (contains? events event-key)
    (throw (ex-info (str "Unknown client event " event-key ". Supported: " (vec (sort (keys events))))
                    {:event event-key :supported (vec (sort (keys events)))})))
  (when-not (var? handler-var)
    (throw (ex-info (str what " for " event-key " must be a var (#'my-handler), got: "
                         (pr-str handler-var)
                         ". Registering the var — not the fn — is what makes the handler hot-reloadable.")
                    {:event event-key :handler handler-var})))
  (interop/ensure-client! (str "The client event " event-key))
  (ensure-fabric-api! event-key))

(defn- register! [event-key wrapper token]
  (when-not (contains? @registrations token)
    (let [{:keys [event listener]} (events event-key)]
      (.register ^net.fabricmc.fabric.api.event.Event (event) (listener wrapper))
      (swap! registrations conj token)))
  event-key)

(defn on!
  "Registers a var as the client event's handler: a function of the event's data
  map (see the ns docstring). Idempotent per [event-key var] pair. Returns
  event-key."
  [event-key handler-var]
  (check-event! event-key handler-var "Handler")
  (let [[build] (contexts event-key)
        wrapper (fn [arg] (handler-var (build arg)))]
    (register! event-key wrapper [event-key handler-var])))

(defn on-pure!
  "Registers a *pure* handler var (style B): a function of the event's data map
  that returns a vector of effects (see skein.fx), run against the event's ctx
  (the client, or the client level for the level-tick events). A nil return runs
  nothing. Idempotent per [event-key var] pair; coexists with an on! handler on
  the same event."
  [event-key handler-var]
  (check-event! event-key handler-var "Pure handler")
  (let [[build ctx-of] (contexts event-key)
        wrapper (fn [arg]
                  (let [data (build arg)
                        effects (handler-var data)]
                    (cond
                      (nil? effects) nil
                      (sequential? effects) (fx/run-effects! (ctx-of arg) effects)
                      :else (throw (ex-info (str "Pure handler " handler-var " for " event-key
                                                 " must return a vector of effects or nil, got: "
                                                 (pr-str effects))
                                            {:event event-key :handler handler-var :returned effects}))))
                  nil)]
    (register! event-key wrapper [event-key handler-var :pure])))

(defn handlers
  "The current registrations: a sorted vector of [event-key var style] triples,
  where style is :fn (an on! handler) or :pure (an on-pure! effect handler)."
  []
  (->> @registrations
       (map (fn [entry] (if (= 3 (count entry)) entry (conj entry :fn))))
       (sort-by (comp str first))
       vec))

(defn catalog
  "A sorted vector of the client event keys, for discovery from the REPL."
  []
  (vec (sort (keys events))))
