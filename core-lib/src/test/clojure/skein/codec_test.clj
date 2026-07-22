(ns skein.codec-test
  "Unit tests for the schema-derived codec. These build real NBT tags, so they
  need the game jar on the classpath — but no booted game: NbtOps is plain
  serialization code."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.codec :as codec])
  (:import (java.util UUID)))

(defn- round-trip
  "The value written as NBT and read back through the same schema."
  [schema value]
  (codec/nbt-> schema (codec/->nbt schema value)))

(defn- why [f]
  (try (f) nil (catch Exception e (.getMessage e))))

(deftest scalars-round-trip
  (is (= "hello" (round-trip :string "hello")))
  (is (= :mymod/ruby (round-trip :keyword :mymod/ruby)))
  (is (= :bare (round-trip :keyword :bare)))
  (is (= 42 (round-trip :int 42)))
  (is (= 2.5 (round-trip :double 2.5)))
  (is (= true (round-trip :boolean true)))
  (is (= false (round-trip :boolean false)))
  (let [u (UUID/randomUUID)]
    (is (= u (round-trip :uuid u))))
  (testing "the predicate spellings are the same leaves"
    (is (= 7 (round-trip 'int? 7)))
    (is (= "x" (round-trip 'string? "x")))))

(deftest maps-round-trip
  (is (= {:n 3 :name "x"} (round-trip [:map [:n :int] [:name :string]] {:n 3 :name "x"})))
  (testing "a qualified key keeps its namespace"
    (is (= {:mymod/n 1} (round-trip [:map [:mymod/n :int]] {:mymod/n 1}))))
  (testing "an optional entry is simply absent"
    (let [s [:map [:n :int] [:note {:optional true} :string]]]
      (is (= {:n 1} (round-trip s {:n 1})))
      (is (= {:n 1 :note "hi"} (round-trip s {:n 1 :note "hi"})))))
  (testing "a :maybe entry keeps its key, with nil"
    (let [s [:map [:n :int] [:note [:maybe :string]]]]
      (is (= {:n 1 :note nil} (round-trip s {:n 1 :note nil})))
      (is (= {:n 1 :note "hi"} (round-trip s {:n 1 :note "hi"}))))))

(deftest collections-round-trip
  (is (= [1 2 3] (round-trip [:vector :int] [1 2 3])))
  (is (= #{:a :b} (round-trip [:set :keyword] #{:a :b})))
  (is (= [1.0 2.0 3.0] (round-trip [:tuple :double :double :double] [1.0 2.0 3.0])))
  (testing "nested collections of maps"
    (let [s [:vector [:map [:pos [:tuple :int :int :int]] [:tags [:set :keyword]]]]
          v [{:pos [1 2 3] :tags #{:a}} {:pos [4 5 6] :tags #{}}]]
      (is (= v (round-trip s v))))))

(deftest map-of-round-trips-by-key-type
  (let [u (UUID/randomUUID)]
    (is (= {u 7} (round-trip [:map-of :uuid :int] {u 7}))))
  (is (= {:a [true false]} (round-trip [:map-of :keyword [:vector :boolean]] {:a [true false]})))
  (is (= {"k" 1} (round-trip [:map-of :string :int] {"k" 1})))
  (is (= {3 :x} (round-trip [:map-of :int :keyword] {3 :x}))))

(deftest enums-round-trip-by-name
  (is (= :ice (round-trip [:enum :fire :ice] :ice)))
  (testing "a value the schema no longer allows names the members"
    (let [stored (codec/->nbt :string "water")
          message (why #(codec/nbt-> [:enum :fire :ice] stored))]
      (is (str/includes? message "[:fire :ice]")))))

(deftest any-is-stored-as-edn
  (let [v {:deep [1 :two "three" {:x #{:y}}]}]
    (is (= {:free v} (round-trip [:map [:free :any]] {:free v})))))

(deftest and-encodes-its-first-branch
  (is (= 5 (round-trip [:and :int [:> 0]] 5))))

(deftest a-value-that-does-not-match-is-refused-with-its-path
  (let [message (why #(codec/->nbt [:map [:n :int]] {:n "three"}))]
    (is (str/includes? message "[:n]"))
    (is (str/includes? message "\"three\""))))

(deftest stored-data-that-no-longer-matches-is-refused
  (testing "a field the schema now requires"
    (let [stored (codec/->nbt [:map [:n :int]] {:n 1})
          message (why #(codec/nbt-> [:map [:n :int] [:extra :string]] stored))]
      (is (str/includes? message "extra"))))
  (testing "a field whose type changed"
    (let [stored (codec/->nbt [:map [:n :string]] {:n "1"})
          message (why #(codec/nbt-> [:map [:n :int]] stored))]
      (is (some? message)))))

(deftest an-unstorable-schema-fails-at-derivation
  (testing "an :or has no single shape to store"
    (let [message (why #(codec/of [:or :int :string]))]
      (is (str/includes? message ":or"))
      (is (str/includes? message ":any"))))
  (testing "a map key that has no name spelling"
    (let [message (why #(codec/of [:map-of [:vector :int] :int]))]
      (is (str/includes? message ":map-of key")))))
