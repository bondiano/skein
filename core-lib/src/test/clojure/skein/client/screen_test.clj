(ns skein.client.screen-test
  "Unit tests for simple-screens-as-data: the screen schema, building a button
  from data, and the button-press seam (that it derefs the current on-click var,
  which is what makes the callback hot-reloadable). Constructing the whole Screen
  and rendering it need a running client and live in a manual / client-gametest
  check."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.client.screen :as screen])
  (:import (net.minecraft SharedConstants DetectedVersion)
           (net.minecraft.client.gui.components Button)
           (net.minecraft.server Bootstrap)))

;; Building a Button coerces its label to a Component; bootstrapping vanilla
;; content is enough, no client. Idempotent; shares the test JVM.
(defonce ^:private _boot
  (do (SharedConstants/setVersion DetectedVersion/BUILT_IN)
      (Bootstrap/bootStrap)
      true))

(defn- why [f]
  (try (f) nil (catch Exception e (.getMessage e))))

(defn- noop [_ctx] nil)

;;; Schema — validation happens before any client class is touched, so ->screen
;;; rejects bad data without needing a running client.

(deftest a-bad-screen-explains-itself
  (testing "a button without an on-click var"
    (is (str/includes? (why #(screen/->screen {:title "x" :widgets [[:button {:text "b" :x 0 :y 0}]]}))
                       ":on-click")))
  (testing "an on-click that is not a var"
    (is (str/includes? (why #(screen/->screen {:title "x"
                                               :widgets [[:button {:text "b" :x 0 :y 0 :on-click noop}]]}))
                       "must be a var")))
  (testing "an unknown widget kind"
    (is (some? (why #(screen/->screen {:title "x" :widgets [[:slider {:x 0 :y 0}]]})))))
  (testing "an unknown key is caught (closed map)"
    (is (str/includes? (why #(screen/->screen {:title "x" :bogus 1})) "[:bogus]"))))

;;; Building a button from data

(deftest a-button-is-built-from-its-data
  (let [^Button b (#'screen/build-button {:text "Click me" :x 10 :y 20 :width 120 :on-click #'noop}
                                         (constantly {}))]
    (is (= "Click me" (.getString (.getMessage b))))
    (is (= 10 (.getX b)))
    (is (= 20 (.getY b)))
    (is (= 120 (.getWidth b)))))

;;; The press seam — reads the current on-click var every press

(def ^:private clicked (atom nil))
(defn- click-a [ctx] (reset! clicked [:a ctx]))
(defn- click-b [_ctx] (reset! clicked :b))

(deftest a-press-runs-the-on-click-var-currently-in-effect
  (let [cb (#'screen/on-press-callback #'click-a (constantly {:client :the-client}))]
    (reset! clicked nil)
    (.onPress cb nil)
    (is (= [:a {:client :the-client}] @clicked) "the handler saw its context")
    (testing "redefining the handler changes what the next press does"
      (let [cb2 (#'screen/on-press-callback #'click-b (constantly {}))]
        (.onPress cb2 nil)
        (is (= :b @clicked))))))
