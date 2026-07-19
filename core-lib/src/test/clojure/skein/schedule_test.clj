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
