(ns skein.data.json-test
  "Unit tests for the dependency-free JSON writer. Pure — no game types."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.data.json :as json]))

(deftest scalars
  (is (= "\"hi\"\n" (json/write "hi")))
  (is (= "42\n" (json/write 42)))
  (is (= "true\n" (json/write true)))
  (is (= "null\n" (json/write nil)))
  (testing "an integer has no decimal, a float keeps it"
    (is (= "9\n" (json/write 9)))
    (is (str/starts-with? (json/write 0.7) "0.7"))))

(deftest keyword-values-drop-the-colon
  (is (= "\"minecraft:stone\"\n" (json/write :minecraft/stone))))

(deftest object-keys-are-sorted
  (is (= "{\n  \"a\": 1,\n  \"b\": 2\n}\n" (json/write {:b 2 :a 1}))))

(deftest strings-escape
  (is (= "\"a\\\"b\\\\c\\nd\"\n" (json/write "a\"b\\c\nd"))))

(deftest nested-and-arrays
  (let [out (json/write {:type "minecraft:block"
                         :pools [{:rolls 1 :entries [{:type "minecraft:item" :name "minecraft:diamond"}]}]})]
    (is (str/includes? out "\"type\": \"minecraft:block\""))
    (is (str/includes? out "\"name\": \"minecraft:diamond\""))
    (is (str/includes? out "["))))

(deftest empty-containers
  (is (= "{}\n" (json/write {})))
  (is (= "[]\n" (json/write [])))
  (is (= "{\n  \"pools\": []\n}\n" (json/write {:pools []}))))

(deftest char-and-string-keys
  (is (str/includes? (json/write {\# "minecraft:diamond"}) "\"#\": \"minecraft:diamond\"")))
