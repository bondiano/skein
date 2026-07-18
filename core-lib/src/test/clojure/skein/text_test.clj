(ns skein.text-test
  (:require [clojure.test :refer [deftest is]]
            [skein.text :as text])
  (:import (net.minecraft.network.chat Component)))

(deftest string-is-a-literal
  (is (instance? Component (text/text "plain")))
  (is (= "plain" (.getString (text/text "plain")))))

(deftest vector-styles-and-appends-children
  (let [c (text/text [:red "error: " [:bold "boom"]])]
    (is (= "error: boom" (.getString c)))
    (is (= :red (:color (text/component->data c))))
    (is (= ["error: " {:bold true :extra ["boom"]}]
           (:extra (text/component->data c))))))

(deftest map-node-round-trips
  (is (= {:text "hi" :color :gold :bold true}
         (text/component->data (text/text {:text "hi" :color :gold :bold true}))))
  (is (= {:text "x" :italic true :underlined true}
         (text/component->data (text/text {:text "x" :italic true :underlined true})))))

(deftest hex-color-round-trips
  (is (= {:text "x" :color "#FFAA00"}
         (text/component->data (text/text {:text "x" :color "#FFAA00"})))))

(deftest bare-literal-reads-back-as-string
  (is (= "plain" (text/component->data (text/text "plain")))))

(deftest component-passes-through
  (let [c (Component/literal "keep")]
    (is (identical? c (text/text c)))))

(deftest unknown-style-keyword-is-explained
  (is (thrown? clojure.lang.ExceptionInfo (text/text [:mauve "x"]))))

(deftest hover-and-click-are-rejected-for-now
  (is (thrown? clojure.lang.ExceptionInfo (text/text {:text "x" :hover [:show-text "t"]}))))
