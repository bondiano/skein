(ns skein.events-test
  (:require [clojure.test :refer [deftest is]]
            [skein.events :as events]))

;; A pure handler is an ordinary function of the event-data map returning a
;; vector of effects — testable directly, no game required. This is the whole
;; point of style B.
(defn- on-use [ev]
  [[:set-block (:pos ev) :mymod/ruby-block]
   [:reply [:green "ok"]]])

(deftest pure-handler-is-a-plain-data-function
  (is (= [[:set-block [10 64 10] :mymod/ruby-block]
          [:reply [:green "ok"]]]
         (on-use {:pos [10 64 10] :player :p}))))

(deftest on-pure-rejects-an-unknown-event
  (is (thrown? clojure.lang.ExceptionInfo (events/on-pure! :no/such #'on-use))))

(deftest on-pure-requires-a-var
  (is (thrown? clojure.lang.ExceptionInfo (events/on-pure! :block/use on-use))))
