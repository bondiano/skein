(ns skein.schema-test
  "Unit tests for the boundary-validation helpers. Pure data and Malli only —
  no game types, so these run without booting the game."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.schema :as schema]))

(def ^:private Item
  [:map {:closed true}
   [:item :keyword]
   [:count {:optional true} [:int {:min 1 :error/message "should be a positive item count"}]]
   [:components {:optional true}
    [:map [:enchantments {:optional true}
           [:map-of {:error/message "should be a map of id -> level"} :keyword [:int {:min 1}]]]]]])

(deftest explain-str-names-path-expectation-and-fragment
  (let [msg (schema/explain-str Item {:item :ok
                                      :components {:enchantments [:minecraft/sharpness 5]}})]
    (testing "the path into the input"
      (is (str/includes? msg "[:components :enchantments]")))
    (testing "the expectation"
      (is (str/includes? msg "should be a map of id -> level")))
    (testing "the offending fragment and its kind"
      (is (str/includes? msg "got [:minecraft/sharpness 5]"))
      (is (str/includes? msg "(a vector)")))))

(deftest explain-str-nil-when-valid
  (is (nil? (schema/explain-str Item {:item :minecraft/diamond :count 3}))))

(deftest explain-str-collapses-duplicate-lines
  ;; An :or over primitives must not print one line per branch.
  (let [s [:map [:v [:fn {:error/message "should be a keyword, boolean, or int"}
                     (fn [x] (or (keyword? x) (boolean? x) (int? x)))]]]
        msg (schema/explain-str s {:v [1 2]})]
    (is (= 1 (count (str/split-lines msg))))))

(deftest errors-returns-structured-data
  (let [errs (schema/errors Item {:item :ok :count 0})]
    (is (= [:count] (:at (first errs))))
    (is (= 0 (:value (first errs))))
    (is (str/includes? (:message (first errs)) "positive item count"))))

(deftest validate!-throws-actionable-and-returns-valid
  (testing "valid value passes through"
    (is (= {:item :x} (schema/validate! Item {:item :x} "item"))))
  (testing "invalid value throws a schema error naming the subject"
    (let [e (try (schema/validate! Item {:item :x :count 0} "item")
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (:skein/schema-error (ex-data e)))
      (is (str/includes? (.getMessage e) "Invalid item"))
      (is (str/includes? (.getMessage e) "[:count]")))))

(deftest guarded-fast-path-returns-build-result
  (is (= :built (schema/guarded Item {:item :x} "item" (fn [] :built)))))

(deftest guarded-enriches-structural-build-failure
  ;; The build throws a cryptic low-level error; the schema pins the real fault.
  (let [e (try (schema/guarded Item {:item :x :count 0} "item"
                               (fn [] (throw (ClassCastException. "cannot cast"))))
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (:skein/schema-error (ex-data e)))
    (is (str/includes? (.getMessage e) "[:count]"))
    (is (instance? ClassCastException (.getCause e)))))

(deftest guarded-passes-through-semantic-error
  ;; A valid-shaped value whose build fails for a reason the schema cannot see
  ;; (an unregistered id) keeps its own actionable message.
  (let [e (try (schema/guarded Item {:item :x} "item"
                               (fn [] (throw (ex-info "No item registered for :x" {:item :x}))))
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= "No item registered for :x" (.getMessage e)))
    (is (not (:skein/schema-error (ex-data e))))))

(deftest guarded-eager-validation-under-flag
  (testing "off by default: an invalid value only fails if the build does"
    (is (= :built (schema/guarded Item {:item :x :count 0} "item" (fn [] :built)))))
  (testing "on: validates before building"
    (binding [schema/*validate* true]
      (is (thrown? clojure.lang.ExceptionInfo
                   (schema/guarded Item {:item :x :count 0} "item" (fn [] :built)))))))
