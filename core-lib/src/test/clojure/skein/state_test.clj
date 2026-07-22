(ns skein.state-test
  "Unit tests for mod state: declaration, the hot-reload contract (the atom
  survives a namespace reload) and the errors a bad declaration produces. The
  world-save half needs a running server and is covered by the integration test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.fx :as fx]
            [skein.state :as state]))

(defn- why [f]
  (try (f) nil (catch Exception e (.getMessage e))))

(defn- fresh-id
  "An id no state has been declared under yet — declarations live in a registry
  for the life of the JVM, which is what keeps values across a reload, so a test
  about a *new* state needs a new id."
  [label]
  (keyword "skein.state-test" (str label "-" (System/nanoTime))))

(deftest a-state-is-an-atom-of-its-init
  (let [scores (state/define! (fresh-id "scores") {:schema [:map-of :uuid :int] :init {}})]
    (is (= {} @scores))
    (swap! scores assoc :a 1)
    (is (= {:a 1} @scores))))

(deftest re-declaring-keeps-the-value
  (testing "the same id hands back the same atom — logic reloads, data does not"
    (let [first-atom (state/define! ::counter {:schema :int :init 0})]
      (reset! first-atom 7)
      (let [second-atom (state/define! ::counter {:schema :int :init 0})]
        (is (identical? first-atom second-atom))
        (is (= 7 @second-atom))))))

;; The var a mod declares in its own namespace, at the top level as mod code
;; would write it.
(state/defstate demo-state {:schema :int :init 1})

(deftest defstate-defines-a-var-holding-the-atom
  (is (instance? clojure.lang.Atom demo-state))
  (reset! demo-state 5)
  (testing "re-evaluating the form keeps the current value"
    (eval '(skein.state/defstate demo-state {:schema :int :init 1}))
    (is (= 5 @demo-state))))

(deftest declared-lists-what-a-mod-holds
  (let [id (fresh-id "listed")]
    (state/define! id {:schema :int :init 3})
    (is (= {:persist? false :saved? false :value 3} (get (state/declared) id)))))

(deftest an-id-is-derived-from-the-namespace-and-the-name
  (is (= :skein.state-test/scores (state/derived-id 'skein.state-test 'scores)))
  (testing "a name that is not a legal content id says so, and how to fix it"
    (let [message (why #(state/derived-id 'MyMod.Core 'scores))]
      (is (str/includes? message ":id")))))

(deftest a-bad-declaration-explains-itself
  (testing "persistence without a schema"
    (let [message (why #(state/define! ::no-schema {:persist? true :init {}}))]
      (is (str/includes? message ":schema"))))
  (testing "a schema that cannot be stored fails at declaration, not at save time"
    (let [message (why #(state/define! ::unstorable {:schema [:or :int :string] :init 1 :persist? true}))]
      (is (str/includes? message ":or"))))
  (testing "an init value that does not match its schema"
    (let [message (why #(state/define! ::bad-init {:schema [:map-of :uuid :int] :init []}))]
      (is (str/includes? message "initial value"))))
  (testing "a misspelled option"
    (let [message (why #(state/define! ::typo {:schema :int :init 1 :persists true}))]
      (is (str/includes? message ":persists")))))

(deftest an-undeclared-state-names-what-is-declared
  (let [message (why #(state/state ::nope))]
    (is (str/includes? message "No state declared"))))

(deftest state-is-updatable-as-an-effect
  (let [id (fresh-id "effect-counter")
        counter (state/define! id {:schema :int :init 0})]
    (fx/run-effects! nil [[:swap-state id + 5]])
    (is (= 5 @counter))))
