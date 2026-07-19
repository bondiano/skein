(ns skein.command-test
  (:require [clojure.test :refer [deftest is]]
            [skein.command :as command]
            [skein.fx :as fx]))

(def ^:private normalize-node #'skein.command/normalize-node)
(def ^:private collect-leaves #'skein.command/collect-leaves)
(def ^:private run-result! #'skein.command/run-result!)

(defn- norm [command-name spec]
  (normalize-node command-name [(name command-name)] spec))

;;; Normalization — the pure data core, no game needed.

(deftest normalizes-args-permission-and-path
  (let [node (norm :heal {:args [[:target :entity] [:amount :int]]
                          :permission :gamemasters
                          :run (fn [_] nil)})]
    (is (= "heal" (:literal node)))
    (is (= ["heal"] (:path node)))
    (is (= [{:name "target" :type :entity} {:name "amount" :type :int}] (:args node)))
    (is (= {:level :gamemasters} (:permission node)))
    (is (:run? node))))

(deftest permission-accepts-level-int-and-predicate
  (is (= {:level-id 2} (:permission (norm :a {:permission 2 :run (fn [_] nil)}))))
  (let [p (fn [_] true)]
    (is (= {:pred p} (:permission (norm :a {:permission p :run (fn [_] nil)})))))
  (is (nil? (:permission (norm :a {:run (fn [_] nil)})))))

(deftest subcommands-nest-with-computed-paths
  (let [node (norm :home {:subs {:set {:run (fn [_] nil)}
                                 :go  {:args [[:name :word]] :run (fn [_] nil)}}})
        by-literal (into {} (map (juxt :literal identity)) (:subs node))]
    (is (= ["home"] (:path node)))
    (is (false? (:run? node)))
    (is (= ["home" "set"] (:path (by-literal "set"))))
    (is (= ["home" "go"] (:path (by-literal "go"))))
    (is (= [{:name "name" :type :word}] (:args (by-literal "go"))))))

(deftest collect-leaves-keys-runnable-nodes-by-path
  (let [node (norm :home {:subs {:set {:run (fn [_] :set)}
                                 :go  {:run (fn [_] :go)}}})
        leaves (collect-leaves node)]
    (is (= #{["home" "set"] ["home" "go"]} (set (keys leaves))))
    (is (= :set ((:run (leaves ["home" "set"])) {})))))

;;; Validation errors.

(deftest rejects-unknown-command-keys
  (is (thrown? clojure.lang.ExceptionInfo (norm :x {:run (fn [_] nil) :bogus 1}))))

(deftest rejects-unknown-argument-type
  (is (thrown? clojure.lang.ExceptionInfo (norm :x {:args [[:a :thing]] :run (fn [_] nil)}))))

(deftest rejects-both-args-and-subs
  (is (thrown? clojure.lang.ExceptionInfo
               (norm :x {:args [[:a :int]] :subs {:s {:run (fn [_] nil)}} :run (fn [_] nil)}))))

(deftest rejects-a-node-with-neither-run-nor-subs
  (is (thrown? clojure.lang.ExceptionInfo (norm :x {:permission 2}))))

(deftest rejects-a-bad-permission
  (is (thrown? clojure.lang.ExceptionInfo (norm :x {:permission :wizard :run (fn [_] nil)})))
  (is (thrown? clojure.lang.ExceptionInfo (norm :x {:permission 9 :run (fn [_] nil)}))))

(deftest def-rejects-a-non-name
  (is (thrown? clojure.lang.ExceptionInfo (command/def! 123 {:run (fn [_] nil)}))))

;;; Result handling — effects, integer, nil.

(def ^:private run-log (atom []))
(defmethod fx/fx! ::note [_ctx [_ v]] (swap! run-log conj v) nil)

(deftest run-result-runs-an-effect-vector
  (reset! run-log [])
  (is (= 1 (run-result! :src [[::note :x] [::note :y]])))
  (is (= [:x :y] @run-log)))

(deftest run-result-passes-an-integer-through
  (is (= 5 (run-result! :src 5))))

(deftest run-result-treats-nil-as-success
  (is (= 1 (run-result! :src nil))))

;;; The built-in :reply effect is registered when the ns loads.

(deftest reply-effect-is-registered
  (is (contains? (set (fx/known-effects)) :reply)))

;;; Storing a command (the Fabric listener pre-marked so registration is
;;; skipped — that path fires only in-game).

(deftest def-stores-a-command
  (swap! @#'skein.command/registry assoc :listener true)
  (command/def! :ping {:run (fn [_] nil)})
  (is (some #{:ping} (command/defined))))
