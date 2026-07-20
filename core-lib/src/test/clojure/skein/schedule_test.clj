(ns skein.schedule-test
  (:require [clojure.test :refer [deftest is]]
            [skein.schedule :as schedule]))

(def ^:private state @#'skein.schedule/state)
(def ^:private run-tick! #'skein.schedule/run-tick!)

;; Pre-mark the tick handler as registered so scheduling touches no Fabric event
;; machinery, then drive ticks by hand.
(defn- reset-state! []
  (reset! state {:tick 0 :next-id 0 :tasks {} :registered true}))

(deftest after-fires-once-at-the-delay
  (reset-state!)
  (let [hits (atom 0)]
    (schedule/after 3 #(swap! hits inc))
    (dotimes [_ 2] (run-tick! nil))
    (is (= 0 @hits) "not yet due")
    (run-tick! nil)
    (is (= 1 @hits) "due on the 3rd tick")
    (dotimes [_ 5] (run-tick! nil))
    (is (= 1 @hits) "does not repeat")))

(deftest every-fires-on-its-period
  (reset-state!)
  (let [hits (atom 0)]
    (schedule/every 2 #(swap! hits inc))
    (dotimes [_ 6] (run-tick! nil))
    (is (= 3 @hits) "ticks 2, 4, 6")))

(deftest cancel-stops-a-repeating-task
  (reset-state!)
  (let [hits (atom 0)
        stop (schedule/every 2 #(swap! hits inc))]
    (dotimes [_ 2] (run-tick! nil))
    (is (= 1 @hits))
    (stop)
    (dotimes [_ 10] (run-tick! nil))
    (is (= 1 @hits) "no fires after cancel")))

(deftest a-non-positive-delay-is-rejected
  (reset-state!)
  (is (thrown? clojure.lang.ExceptionInfo (schedule/after 0 #(do)))))

(deftest keyed-reschedule-replaces-instead-of-duplicating
  (reset-state!)
  (let [hits (atom 0)]
    ;; Simulate re-evaluating the same ns three times.
    (dotimes [_ 3] (schedule/every 2 #(swap! hits inc) {:id :mymod/beat}))
    (is (= 1 (count (:tasks @state))) "only one timer for the id")
    (dotimes [_ 6] (run-tick! nil))
    (is (= 3 @hits) "fires once per period, not once per re-eval")))

(deftest keyed-and-unkeyed-coexist
  (reset-state!)
  (schedule/every 2 #(do) {:id :mymod/beat})
  (schedule/every 2 #(do))
  (schedule/every 2 #(do))
  (is (= 3 (count (:tasks @state))) "unkeyed calls each add a distinct timer"))

(deftest keyed-cancel-stops-the-task
  (reset-state!)
  (let [hits (atom 0)
        stop (schedule/after 2 #(swap! hits inc) {:id :mymod/once})]
    (stop)
    (dotimes [_ 5] (run-tick! nil))
    (is (= 0 @hits))))
