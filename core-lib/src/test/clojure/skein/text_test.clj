(ns skein.text-test
  (:require [clojure.test :refer [deftest is]]
            [skein.text :as text])
  (:import (net.minecraft SharedConstants DetectedVersion)
           (net.minecraft.network.chat Component)
           (net.minecraft.server Bootstrap)))

;; The :show-item / :show-entity hovers look items and entity types up in the
;; registries, so those tests need vanilla content bootstrapped (no game or
;; world). Idempotent — sharing the test JVM with the other suites is fine.
(defonce ^:private _boot
  (do (SharedConstants/setVersion DetectedVersion/BUILT_IN)
      (Bootstrap/bootStrap)
      true))

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

(deftest translate-without-args
  (is (= [:translate "block.mymod.ruby"]
         (text/component->data (text/text [:translate "block.mymod.ruby"]))))
  (is (= "block.mymod.ruby" (.getString (text/text [:translate "block.mymod.ruby"])))))

(deftest translate-with-args-round-trips
  (is (= [:translate "chat.type.text" "Steve" "hi"]
         (text/component->data (text/text [:translate "chat.type.text" "Steve" "hi"])))))

(deftest translate-needs-a-string-key
  (is (thrown? clojure.lang.ExceptionInfo (text/text [:translate 5]))))

(deftest click-run-command-round-trips
  (is (= {:text "[go]" :color :aqua :click {:action :run-command :value "/spawn"}}
         (text/component->data
          (text/text {:text "[go]" :color :aqua
                      :click {:action :run-command :value "/spawn"}})))))

(deftest every-click-action-round-trips
  (doseq [[action value] [[:run-command "/a"] [:suggest-command "/b "]
                          [:copy-to-clipboard "xyz"] [:change-page 3]]]
    (is (= {:text "c" :click {:action action :value value}}
           (text/component->data (text/text {:text "c" :click {:action action :value value}})))
        (str action))))

(deftest open-url-round-trips-as-string
  (is (= {:text "u" :click {:action :open-url :value "https://example.com"}}
         (text/component->data (text/text {:text "u" :click {:action :open-url :value "https://example.com"}})))))

(deftest hover-show-text-round-trips
  (is (= {:text "?" :hover {:action :show-text :value {:italic true :extra ["tip"]}}}
         (text/component->data
          (text/text {:text "?" :hover {:action :show-text :value [:italic "tip"]}})))))

(deftest hover-show-item-and-entity-build
  ;; These need the registries (bootstrapped above). Reverse is by action tag.
  (is (= :show-item
         (:action (:hover (text/component->data
                           (text/text {:text "g" :hover {:action :show-item
                                                         :value {:item :minecraft/diamond :count 2}}}))))))
  (is (= :show-entity
         (:action (:hover (text/component->data
                           (text/text {:text "m" :hover {:action :show-entity
                                                         :value {:type :minecraft/zombie
                                                                 :uuid #uuid "00000000-0000-0000-0000-000000000001"
                                                                 :name "Bob"}}})))))))

(deftest bad-action-is-explained
  (is (thrown? clojure.lang.ExceptionInfo (text/text {:text "x" :hover {:action :nope :value 1}})))
  (is (thrown? clojure.lang.ExceptionInfo (text/text {:text "x" :click {:action :nope :value 1}})))
  (is (thrown? clojure.lang.ExceptionInfo (text/text {:text "x" :hover "not-a-map"}))))
