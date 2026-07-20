(ns skein.inspect-test
  "Unit tests for the tap> buffer. tap> delivers on a background thread, so the
  tests poll for the expected count rather than assuming synchronous delivery.
  The game-state dumps (dump / entities-around / blocks-around) need a live
  server and are exercised by the L2 integration tests, not here."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skein.inspect :as inspect]))

(defn- wait-for-count [n]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (= n (count (inspect/taps))) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(use-fixtures :each (fn [t] (inspect/clear-taps!) (t)))

(deftest tap-values-are-buffered-oldest-first
  (tap> {:a 1})
  (tap> [:hit [10 64 10]])
  (tap> :ping)
  (is (wait-for-count 3))
  (is (= [{:a 1} [:hit [10 64 10]] :ping] (inspect/taps :values)))
  (is (= :ping (:value (inspect/last-tap)))))

(deftest entries-carry-a-sequence-number-and-timestamp
  (tap> :x)
  (is (wait-for-count 1))
  (let [e (inspect/last-tap)]
    (is (integer? (:n e)))
    (is (integer? (:ms e)))
    (is (= :x (:value e)))))

(deftest capacity-keeps-the-most-recent
  (dotimes [i 5] (tap> i))
  (is (wait-for-count 5))
  (inspect/capacity! 2)
  (is (= [3 4] (inspect/taps :values)))
  ;; restore a generous capacity for the other tests
  (inspect/capacity! 128))

(deftest clear-empties-the-buffer
  (tap> :a)
  (is (wait-for-count 1))
  (inspect/clear-taps!)
  (is (= [] (inspect/taps))))

(deftest uninstall-stops-capture-reinstall-resumes
  (inspect/uninstall-tap!)
  (tap> :dropped)
  (Thread/sleep 40)
  (is (= [] (inspect/taps)))
  (inspect/install-tap!)
  (tap> :kept)
  (is (wait-for-count 1))
  (is (= [:kept] (inspect/taps :values))))
