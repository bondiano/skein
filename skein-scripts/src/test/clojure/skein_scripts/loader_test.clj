(ns skein-scripts.loader-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skein-scripts.loader :as loader])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; Scripts write here so a test can assert they ran and in what order.
(def sink (atom []))

(defn- temp-dir ^java.io.File []
  (.toFile (Files/createTempDirectory "skein-scripts-test" (make-array FileAttribute 0))))

(defn- spit-script [dir file-name content]
  (spit (io/file dir file-name) content))

(defn- silent [& _])

(defn- config-for [dir]
  {:scripts-dir dir
   :repo-dir (io/file dir "repo")
   ;; No deps.edn — deps resolution is a no-op, tools.deps is never touched.
   :deps-file (io/file dir "no-such-deps.edn")
   :offline? false})

(deftest list-scripts-sorted-and-filtered
  (let [dir (temp-dir)]
    (spit-script dir "b.clj" "1")
    (spit-script dir "a.clj" "1")
    (spit-script dir "notes.txt" "not a script")
    (is (= ["a.clj" "b.clj"] (mapv #(.getName %) (loader/list-scripts dir))))
    (testing "missing directory is empty, not an error"
      (is (= [] (loader/list-scripts (io/file dir "does-not-exist")))))))

(deftest loads-in-name-order-and-isolates-errors
  (reset! sink [])
  (let [dir (temp-dir)]
    (spit-script dir "20-b.clj" "(swap! skein-scripts.loader-test/sink conj :b)")
    (spit-script dir "10-a.clj" "(swap! skein-scripts.loader-test/sink conj :a)")
    (spit-script dir "15-bad.clj" "(throw (RuntimeException. \"boom\"))")
    (let [status (loader/load-all! (config-for dir) silent)
          by-name (into {} (map (juxt :name :status)) (:scripts status))]
      (testing "good scripts ran in sorted order despite the failure between them"
        (is (= [:a :b] @sink)))
      (testing "per-script status: the broken one is isolated, the rest ok"
        (is (= :ok (by-name "10-a.clj")))
        (is (= :ok (by-name "20-b.clj")))
        (is (= :error (by-name "15-bad.clj")))
        (is (= 3 (count (:scripts status)))))
      (testing "no deps.edn -> deps resolution reports :none"
        (is (= :none (:status (:deps status))))))))

(deftest empty-directory-loads-nothing
  (let [status (loader/load-all! (config-for (temp-dir)) silent)]
    (is (= [] (:scripts status)))
    (is (some? (:loaded-at status)))))
