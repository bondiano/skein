(ns skein.schemas-test
  "Unit tests for the data-format schemas. Pure — no game types."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.schema :as schema]
            [skein.schemas :as schemas]))

(defn- why [s v] (schema/explain-str s v))

(deftest item-map-accepts-valid
  (is (nil? (why schemas/item-map {:item :minecraft/diamond})))
  (is (nil? (why schemas/item-map {:item :mymod/ruby :count 5
                                   :components {:custom-name [:gold "Blade"] :damage 3}}))))

(deftest item-map-rejects-with-path
  (testing "bad count"
    (is (str/includes? (why schemas/item-map {:item :x :count 0}) "[:count]")))
  (testing "unknown key is caught (closed map)"
    (is (str/includes? (why schemas/item-map {:item :x :bogus 1}) "[:bogus]")))
  (testing "bad component value names the nested path"
    (is (str/includes? (why schemas/item-map {:item :x :components {:damage "no"}})
                       "[:components :damage]"))))

(deftest component-map-is-open-for-mod-components
  (is (nil? (why schemas/item-map {:item :x :components {:mymod/charge 10}}))))

(deftest blockstate-map-validates-props
  (is (nil? (why schemas/blockstate-map {:block :minecraft/oak_stairs :props {:facing :north :half :top}})))
  (is (str/includes? (why schemas/blockstate-map {:block :x :props {:facing [1 2]}})
                     "[:props :facing]")))

(deftest register-decl-catches-typos-and-bare-ids
  (testing "misspelled block key"
    (is (str/includes? (why schemas/register-decl {:blocks {:mymod/ruby {:strngth 3.0}}})
                       "[:blocks :mymod/ruby :strngth]")))
  (testing "a bare (namespace-less) id is rejected with a fix"
    (let [msg (why schemas/register-decl {:blocks {:ruby {:strength 3.0}}})]
      (is (str/includes? msg "[:blocks :ruby]"))
      (is (str/includes? msg "namespace"))))
  (testing "bad strength shape"
    (is (str/includes? (why schemas/register-decl {:blocks {:mymod/ruby {:strength "hard"}}})
                       "hardness resistance")))
  (testing "bad rarity enum"
    (is (str/includes? (why schemas/register-decl {:items {:mymod/g {:rarity :legendary}}})
                       ":common"))))

(deftest register-decl-accepts-valid
  (is (nil? (why schemas/register-decl
                 {:blocks {:mymod/ruby-block {:strength [3.0 6.0] :requires-tool true :light-level 7}}
                  :items {:mymod/ruby {:rarity :rare :group :ingredients}}}))))

(deftest register-decl-rejects-non-map
  (is (some? (why schemas/register-decl [1 2 3]))))

(deftest validator-cache-returns-working-predicate
  (let [v (schemas/validator schemas/item-map)]
    (is (true? (v {:item :x})))
    (is (false? (v {:item :x :count 0})))
    (is (identical? v (schemas/validator schemas/item-map)) "cached")))
