(ns skein.id-test
  (:require [clojure.test :refer [deftest is]]
            [skein.id :as id])
  (:import (net.minecraft.resources Identifier)))

(deftest parts-is-pure
  (is (= ["minecraft" "diamond"] (id/parts :diamond)))
  (is (= ["mymod" "ruby"] (id/parts :mymod/ruby)))
  (is (= ["minecraft" "diamond"] (id/parts "diamond")))
  (is (= ["mymod" "ruby"] (id/parts "mymod:ruby"))))

(deftest parts-rejects-nonsense
  (is (thrown? clojure.lang.ExceptionInfo (id/parts 42))))

(deftest coerces-to-identifier
  (is (instance? Identifier (id/id :diamond)))
  (is (= "minecraft:diamond" (str (id/id :diamond))))
  (is (= "mymod:ruby" (str (id/id :mymod/ruby))))
  (is (= "mymod:ruby" (str (id/id "mymod:ruby")))))

(deftest identifier-passes-through
  (let [ident (id/id :mymod/ruby)]
    (is (identical? ident (id/id ident)))))

(deftest id->kw-inverts-id
  (is (= :minecraft/diamond (id/id->kw (id/id :diamond))))
  (is (= :mymod/ruby (id/id->kw (id/id :mymod/ruby)))))
