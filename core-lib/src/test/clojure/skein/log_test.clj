(ns skein.log-test
  "Unit tests for the logging bridge. Uses whatever tools.logging backend is on
  the test classpath (java.util.logging by default) — the point here is that the
  macros expand, delegate, and evaluate lazily, not which backend receives them."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.log :as log]))

(deftest level-macros-log-without-throwing
  (is (nil? (log/info "an info line" {:k 1})))
  (is (nil? (log/warn "a warning")))
  (is (nil? (log/errorf "code %d" 7))))

(deftest debug-is-lazy-when-disabled
  ;; When a level is suppressed, tools.logging must not evaluate the message
  ;; arguments. Only assert it when debug is actually disabled for this ns, so
  ;; the test does not depend on the backend's configured level.
  (when-not (log/enabled? :debug (str *ns*))
    (testing "a suppressed debug does not evaluate its arguments"
      (is (nil? (log/debug (throw (RuntimeException. "must not run"))))))))

(deftest enabled?-and-logger
  (is (boolean? (log/enabled? :info "skein-test")))
  (is (some? (log/logger :mymod)))
  (is (some? (log/logger "mymod.combat"))))
