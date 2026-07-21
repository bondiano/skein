(ns skein.data.schema-test
  "Unit tests for the skein-data.edn schema. Pure — no game types."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.schema :as schema]
            [skein.data.schema :as ds]))

(defn- why [v] (schema/explain-str ds/data v))

(deftest accepts-a-full-valid-document
  (is (nil? (why {:lang {:en_us {"block.mymod.ruby" "Ruby"}
                         :ru_ru {:block/mymod.ruby "Рубин"}}
                  :tags {:block {"minecraft:mineable/pickaxe" [:mymod/ruby_ore]}
                         :item {:mymod/gems [:mymod/ruby "#minecraft:diamonds"]}}
                  :recipes {:mymod/ruby_block {:type :shaped
                                               :category :building
                                               :pattern ["###" "###" "###"]
                                               :key {\# :mymod/ruby}
                                               :result :mymod/ruby_block}
                            :mymod/ruby {:type :shapeless
                                         :ingredients [:mymod/ruby_block]
                                         :result {:id :mymod/ruby :count 9}}
                            :mymod/smelt {:type :smelting :ingredient :mymod/ruby_ore
                                          :result :mymod/ruby :experience 0.7 :cookingtime 200}
                            :mymod/cut {:type :stonecutting :ingredient :mymod/ruby_block
                                        :result {:id :mymod/ruby :count 9}}}
                  :loot {:mymod/ruby_block {:type :block}
                         :mymod/ruby_ore {:type :block :drop :mymod/ruby}}
                  :advancements {:mymod/root {:type :raw :json {:display {}}}}}))))

(deftest empty-document-is-valid
  (is (nil? (why {}))))

(deftest closed-map-catches-a-typo
  (is (str/includes? (why {:recipes {:mymod/x {:type :shaped :pattrn ["#"]
                                               :key {\# :mymod/ruby} :result :mymod/ruby}}})
                     ":pattrn")))

(deftest recipe-needs-a-known-type
  (is (some? (why {:recipes {:mymod/x {:type :bogus}}}))))

(deftest smelting-requires-ingredient
  (is (some? (why {:recipes {:mymod/x {:type :smelting :result :mymod/ruby}}}))))

(deftest tags-shape
  (testing "a tag must map to a vector of members"
    (is (some? (why {:tags {:block {:mymod/t :not-a-vector}}})))))

(deftest lang-values-are-strings
  (is (some? (why {:lang {:en_us {"k" 5}}}))))

(deftest raw-escape-hatches
  (is (nil? (why {:recipes {:mymod/x {:type :raw :json {:type "minecraft:special"}}}})))
  (is (nil? (why {:loot {:mymod/x {:type :raw :json {:pools []}}}}))))
