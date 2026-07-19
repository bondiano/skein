(ns skein.schedule
  "Tick timers over the server tick.

      (schedule/after 20 #(broadcast! \"20 ticks later\"))   ; once, after 20 ticks
      (def stop (schedule/every 100 #'heartbeat))           ; every 100 ticks
      (stop)                                                 ; cancel it

  `after` runs the thunk once; `every` runs it on a period. Both return a
  zero-arg cancel function. Pass a var (`#'heartbeat`) to keep the callback
  hot-reloadable — it is dereferenced on each fire.

  A single server-tick handler drives every timer, registered lazily on the
  first schedule call (needs fabric-api, like skein.events)."
  (:require [skein.events :as events]))

(defonce ^:private state (atom {:tick 0 :next-id 0 :tasks {} :registered false}))

(defn- run-tick! [_server]
  (let [now (:tick (swap! state update :tick inc))]
    (doseq [[id {:keys [due interval f]}] (:tasks @state)]
      (when (>= now due)
        (f)
        (if interval
          (swap! state assoc-in [:tasks id :due] (+ now interval))
          (swap! state update :tasks dissoc id))))))

(defn- ensure-registered! []
  (when-not (:registered @state)
    (events/on! :server/tick-end #'run-tick!)
    (swap! state assoc :registered true)))

(defn- schedule! [delay interval f]
  (when-not (and (integer? delay) (pos? delay))
    (throw (ex-info (str "schedule delay must be a positive number of ticks, got: " (pr-str delay)) {:delay delay})))
  (ensure-registered!)
  (let [id (:next-id (swap! state update :next-id inc))
        due (+ (:tick @state) delay)]
    (swap! state assoc-in [:tasks id] {:due due :interval interval :f f})
    (fn cancel [] (swap! state update :tasks dissoc id) nil)))

(defn after
  "Runs the zero-arg thunk once, `ticks` server ticks from now. Returns a
  cancel function."
  [ticks f]
  (schedule! ticks nil f))

(defn every
  "Runs the zero-arg thunk every `ticks` server ticks, starting `ticks` from
  now. Returns a cancel function. Pass a var for a hot-reloadable callback."
  [ticks f]
  (schedule! ticks ticks f))
