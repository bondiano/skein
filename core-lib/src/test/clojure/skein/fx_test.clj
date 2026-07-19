(ns skein.fx-test
  (:require [clojure.test :refer [deftest is]]
            [skein.fx :as fx]))

;; A test effect that mutates its ctx (an atom) — the same shape a real effect
;; has, delegating to a mutation instead of an atom.
(defmethod fx/fx! ::append [ctx [_ v]] (swap! ctx conj v) nil)

(deftest runs-effects-in-order
  (let [log (atom [])]
    (fx/run-effects! log [[::append :a] [::append :b] [::append :c]])
    (is (= [:a :b :c] @log))))

(deftest empty-effects-is-a-no-op
  (let [log (atom [])]
    (fx/run-effects! log [])
    (is (= [] @log))))

(deftest known-effects-lists-registered-kinds
  (is (contains? (set (fx/known-effects)) ::append))
  (is (not (contains? (set (fx/known-effects)) :default))))

(deftest effect-kind-reads-the-head
  (is (= :set-block (fx/effect-kind [:set-block [1 2 3] :stone]))))

(deftest a-bare-value-is-not-an-effect
  (is (thrown? clojure.lang.ExceptionInfo (fx/effect-kind :set-block)))
  (is (thrown? clojure.lang.ExceptionInfo (fx/effect-kind ["set-block"]))))

(deftest unknown-effect-is-explained
  (let [ex (try (fx/run-effects! (atom []) [[::nope 1]])
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= ::nope (:kind (ex-data ex))))
    (is (contains? (set (:known (ex-data ex))) ::append))))

(deftest run-effects-rejects-a-non-sequence
  (is (thrown? clojure.lang.ExceptionInfo (fx/run-effects! (atom []) :not-a-vector))))
