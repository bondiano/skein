(ns skein.client.keys-test
  "Unit tests for keybindings-as-data: the declaration schema, the coercion of
  key/category data to game objects, and the press-dispatch seam (that it reads
  the current handler var, which is what makes it hot-reloadable). Registering a
  KeyMapping with the game and polling real presses needs a running client and
  lives in a manual/client-gametest check."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.client.keys :as keys]
            [skein.fx :as fx])
  (:import (com.mojang.blaze3d.platform InputConstants)
           (net.minecraft SharedConstants DetectedVersion)
           (net.minecraft.client KeyMapping)
           (net.minecraft.server Bootstrap)))

;; Building a KeyMapping and reading vanilla key categories needs vanilla
;; content bootstrapped — no game or world. Idempotent; shares the test JVM.
(defonce ^:private _boot
  (do (SharedConstants/setVersion DetectedVersion/BUILT_IN)
      (Bootstrap/bootStrap)
      true))

(defn- why [f]
  (try (f) nil (catch Exception e (.getMessage e))))

;;; Declaration

(deftest a-bad-declaration-explains-itself
  (testing "a missing name is caught by the schema"
    (is (str/includes? (why #(keys/define! :mymod/open {:key "key.keyboard.g"})) "[:name]")))
  (testing "an id that is not namespace-qualified"
    (is (str/includes? (why #(keys/define! :open {:name "key.mymod.open"})) ":mymod/open"))))

;;; Coercion of the declaration data to game objects

(deftest a-key-name-becomes-an-input-key
  (let [k (#'keys/->key "key.keyboard.g")]
    (is (= "key.keyboard.g" (.getName k))))
  (testing "no key means an unbound binding"
    (is (= InputConstants/UNKNOWN (#'keys/->key nil))))
  (testing "a nonsense key name is explained"
    (is (str/includes? (why #(#'keys/->key "not.a.key")) "key.keyboard.g"))))

(deftest a-category-keyword-names-a-vanilla-group
  (is (some? (#'keys/->category :misc)))
  (is (some? (#'keys/->category nil)))
  (testing "an unknown vanilla category is explained"
    (is (str/includes? (why #(#'keys/->category :nope)) "Unknown keybinding category"))))

(deftest a-mapping-is-built-from-the-declaration
  (let [^KeyMapping km (#'keys/build-mapping {:name "key.mymod.open" :key "key.keyboard.g"})]
    (is (= "key.mymod.open" (.getName km)))))

;;; The press-dispatch seam — reads the current handler var every press

(def ^:private pressed (atom nil))
(defn- on-press [data] (reset! pressed data))
(defn- on-press-again [data] (reset! pressed [:again (:key data)]))

(deftest a-press-runs-the-handler-currently-registered-for-the-binding
  (let [id :skein_keys_test/probe]
    ;; Poke the registry directly — define! would try to talk to a client that
    ;; does not exist in a unit-test JVM.
    (swap! @#'keys/bindings assoc id {:decl {} :mapping nil :handler #'on-press :style :fn})
    (reset! pressed nil)
    (#'keys/dispatch! id :the-client)
    (is (= {:key id :client :the-client :side :client} @pressed))
    (testing "redefining the handler changes what the next press does"
      (swap! @#'keys/bindings assoc-in [id :handler] #'on-press-again)
      (#'keys/dispatch! id :the-client)
      (is (= [:again id] @pressed)))))

(def ^:private ran (atom nil))
(defmethod fx/fx! :skein_keys_test/record [ctx [_ v]] (reset! ran [ctx v]))
(defn- on-press-pure [data] [[:skein_keys_test/record (:key data)]])

(deftest a-pure-handler-runs-its-effects-against-the-client
  (let [id :skein_keys_test/pure]
    (swap! @#'keys/bindings assoc id {:decl {} :mapping nil :handler #'on-press-pure :style :pure})
    (reset! ran nil)
    (#'keys/dispatch! id :the-client)
    (is (= [:the-client id] @ran) "the effect ran against the client")))

;;; Introspection

(deftest declared-reports-bindings-as-data
  (swap! @#'keys/bindings assoc :skein_keys_test/shown
         {:decl {:name "key.mymod.x" :key "key.keyboard.x"} :mapping nil :handler #'on-press :style :fn})
  (let [d (get (keys/declared) :skein_keys_test/shown)]
    (is (= "key.mymod.x" (:name d)))
    (is (= #'on-press (:handler d)))))
