(ns skein.data.ids-test
  "Unit tests for id normalization and the typo-suggestion helper. Pure."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.data.ids :as ids]))

(deftest parts-and-id-str
  (is (= ["minecraft" "diamond"] (ids/parts :diamond)))
  (is (= ["mymod" "ruby"] (ids/parts :mymod/ruby)))
  (is (= ["mymod" "ruby"] (ids/parts "mymod:ruby")))
  (is (= "minecraft:diamond" (ids/id-str :diamond)))
  (is (= "mymod:ruby" (ids/id-str :mymod/ruby)))
  (is (= "mymod:ruby" (ids/id-str "mymod:ruby"))))

(deftest tag-references
  (is (ids/tag-ref? "#minecraft:logs"))
  (is (not (ids/tag-ref? "minecraft:oak_log")))
  (testing "normalize keeps the # and canonicalizes behind it"
    (is (= "#minecraft:logs" (ids/normalize "#minecraft:logs")))
    (is (= "#minecraft:planks" (ids/normalize "#planks")))
    (is (= "minecraft:diamond" (ids/normalize :diamond)))))

(deftest levenshtein-basics
  (is (= 0 (ids/levenshtein "abc" "abc")))
  (is (= 1 (ids/levenshtein "abc" "abd")))
  (is (= 3 (ids/levenshtein "abc" ""))))

(deftest closest-and-did-you-mean
  (let [candidates #{"minecraft:diamond" "minecraft:diamond_block" "minecraft:emerald"}]
    (is (= ["minecraft:diamond"] (ids/closest "minecraft:diamnd" candidates :limit 1)))
    (is (not (contains? (set (ids/closest "minecraft:zzzzzzzzzz" candidates)) "minecraft:emerald")))
    (testing "did-you-mean wraps a suggestion, or is empty when nothing is near"
      (is (re-find #"did you mean" (ids/did-you-mean "minecraft:diamnd" candidates)))
      (is (= "" (ids/did-you-mean "totally:unrelated_xyz" candidates))))))
