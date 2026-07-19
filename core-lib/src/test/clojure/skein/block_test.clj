(ns skein.block-test
  (:require [clojure.test :refer [deftest is]]
            [skein.block :as block])
  (:import (net.minecraft SharedConstants DetectedVersion)
           (net.minecraft.server Bootstrap)))

;; Block coercion needs the block registries populated — bootstrapping vanilla
;; content is enough, no game or world. Idempotent, so sharing the test JVM with
;; the other suites is fine.
(defonce ^:private _boot
  (do (SharedConstants/setVersion DetectedVersion/BUILT_IN)
      (Bootstrap/bootStrap)
      true))

(deftest bare-keyword-gives-the-default-state
  (is (= {:block :minecraft/stone}
         (block/blockstate-> (block/->blockstate :minecraft/stone)))))

(deftest stairs-round-trip-their-properties
  (let [data {:block :minecraft/oak_stairs :props {:facing :north :half :top}}
        back (block/blockstate-> (block/->blockstate data))]
    (is (= :minecraft/oak_stairs (:block back)))
    (is (= :north (get-in back [:props :facing])))
    (is (= :top (get-in back [:props :half])))))

(deftest boolean-property-reads-back-as-a-boolean
  (let [back (block/blockstate-> (block/->blockstate {:block :minecraft/oak_stairs
                                                      :props {:waterlogged true}}))]
    (is (true? (get-in back [:props :waterlogged])))))

(deftest a-blockstate-passes-through
  (let [state (block/->blockstate :minecraft/stone)]
    (is (identical? state (block/->blockstate state)))))

(deftest unknown-block-is-explained
  (is (thrown? clojure.lang.ExceptionInfo (block/->blockstate :minecraft/not_a_block))))

(deftest unknown-property-is-explained
  (is (thrown? clojure.lang.ExceptionInfo
               (block/->blockstate {:block :minecraft/stone :props {:facing :north}}))))

(deftest invalid-property-value-is-explained
  (is (thrown? clojure.lang.ExceptionInfo
               (block/->blockstate {:block :minecraft/oak_stairs :props {:facing :diagonal}}))))
