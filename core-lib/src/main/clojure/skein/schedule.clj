(ns skein.schedule
  "Tick timers over the server tick.

      (schedule/after 20 #(broadcast! \"20 ticks later\"))   ; once, after 20 ticks
      (def stop (schedule/every 100 #'heartbeat))           ; every 100 ticks
      (stop)                                                 ; cancel it

  `after` runs the thunk once; `every` runs it on a period. Both return a
  zero-arg cancel function. Pass a var (`#'heartbeat`) to keep the callback
  hot-reloadable — it is dereferenced on each fire.

  Hot reload — a keyed timer. Re-evaluating a plain `(schedule/every ...)` from
  the REPL starts a *second* timer: unlike events (deduped by var) or commands
  (by name), a bare schedule call has no identity to dedupe on. Pass `:id` to
  give it one, and a re-eval replaces the previous timer instead of stacking a
  duplicate — the idiomatic way to schedule from an ns that gets reloaded:

      (schedule/every 100 #'heartbeat {:id :mymod/heartbeat})   ; re-eval-safe

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

(defn- schedule! [delay interval f user-key]
  (when-not (and (integer? delay) (pos? delay))
    (throw (ex-info (str "schedule delay must be a positive number of ticks, got: " (pr-str delay)) {:delay delay})))
  (ensure-registered!)
  ;; A user-supplied :id is the task key, so re-scheduling with the same id
  ;; replaces the timer; otherwise a fresh numeric id makes every call distinct.
  ;; Numeric and user keys never collide.
  (let [id (if (some? user-key) user-key (:next-id (swap! state update :next-id inc)))
        due (+ (:tick @state) delay)]
    (swap! state assoc-in [:tasks id] {:due due :interval interval :f f})
    (fn cancel [] (swap! state update :tasks dissoc id) nil)))

(defn after
  "Runs the zero-arg thunk once, `ticks` server ticks from now. Returns a
  cancel function. With `{:id k}`, re-scheduling under the same id replaces the
  pending timer instead of adding another (see the ns docstring)."
  ([ticks f] (after ticks f nil))
  ([ticks f {:keys [id]}] (schedule! ticks nil f id)))

(defn every
  "Runs the zero-arg thunk every `ticks` server ticks, starting `ticks` from
  now. Returns a cancel function. Pass a var for a hot-reloadable callback, and
  `{:id k}` so a re-eval replaces this timer rather than stacking a duplicate
  (see the ns docstring)."
  ([ticks f] (every ticks f nil))
  ([ticks f {:keys [id]}] (schedule! ticks ticks f id)))
