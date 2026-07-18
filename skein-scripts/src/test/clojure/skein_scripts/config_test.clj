(ns skein-scripts.config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skein-scripts.config :as config]))

(def ^:private base (io/file "config" "skein"))

(deftest defaults-when-no-properties
  (let [c (config/parse base nil)]
    (is (= :server-started (:phase c)))
    (is (false? (:watch? c)))
    (is (false? (:offline? c)))
    (is (= (io/file base "scripts") (:scripts-dir c)))
    (is (= (io/file base "repo") (:repo-dir c)))
    (is (= (io/file base "deps.edn") (:deps-file c)))))

(deftest phase-parsing
  (testing "explicit phases, underscores and dashes both accepted"
    (is (= :mod-init (:phase (config/parse base {"phase" "mod-init"}))))
    (is (= :mod-init (:phase (config/parse base {"phase" "mod_init"}))))
    (is (= :server-starting (:phase (config/parse base {"phase" " server-starting "})))))
  (testing "unknown phase is rejected loudly"
    (is (thrown? clojure.lang.ExceptionInfo (config/parse base {"phase" "whenever"})))))

(deftest boolean-parsing
  (doseq [truthy ["true" "TRUE" "yes" "on" "1"]]
    (is (true? (:watch? (config/parse base {"watch" truthy})))))
  (doseq [falsy ["false" "no" "off" "0" ""]]
    (is (false? (:watch? (config/parse base {"watch" falsy})))))
  (is (true? (:offline? (config/parse base {"offline" "true"})))))

(deftest directory-overrides
  (let [c (config/parse base {"scripts-dir" "/srv/scripts" "repo-dir" "/srv/repo"})]
    (is (= (io/file "/srv/scripts") (:scripts-dir c)))
    (is (= (io/file "/srv/repo") (:repo-dir c)))))
