(ns skein.client.events-test
  "Unit tests for the client event wrappers: the catalog, the data-map shape a
  handler receives, and the errors a bad registration produces. Registering
  against a live Fabric event needs a running client and lives in a manual/
  client-gametest check — the seam here is the data the wrapper would hand a
  handler."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.client.events :as events]))

(defn- on-tick [_ev] nil)

(deftest catalog-lists-the-client-events
  (let [cat (events/catalog)]
    (is (seq cat))
    (is (contains? (set cat) :client/tick-end))
    (is (contains? (set cat) :client/level-tick-end))))

(deftest an-unknown-event-is-rejected-before-touching-the-client
  (is (thrown? clojure.lang.ExceptionInfo (events/on! :no/such #'on-tick)))
  (is (thrown? clojure.lang.ExceptionInfo (events/on-pure! :no/such #'on-tick))))

(deftest a-handler-must-be-a-var
  (is (thrown? clojure.lang.ExceptionInfo (events/on! :client/tick-end on-tick)))
  (is (thrown? clojure.lang.ExceptionInfo (events/on-pure! :client/tick-end on-tick))))

(deftest the-data-map-shape-a-handler-receives
  (testing "a client event carries the client and the side"
    (is (= {:side :client :client :the-client}
           (#'events/client-data :the-client))))
  (testing "a level-tick event carries the level too"
    (let [m (#'events/level-data :the-level)]
      (is (= :client (:side m)))
      (is (= :the-level (:level m)))
      (is (contains? m :client)))))
