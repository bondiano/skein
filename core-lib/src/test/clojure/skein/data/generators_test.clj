(ns skein.data.generators-test
  "Unit tests for the resource generators (lang/tags/recipe/loot/advancement).
  Pure — no game types; they emit data maps and reference lists."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.data.advancement :as advancement]
            [skein.data.lang :as lang]
            [skein.data.loot :as loot]
            [skein.data.recipe :as recipe]
            [skein.data.tags :as tags]))

(defn- by-path [files path] (some #(when (= path (:path %)) %) files))

;;; Lang

(deftest lang-key-coercion
  (is (= "block.mymod.ruby" (lang/key->str "block.mymod.ruby")))
  (is (= "block.mymod.ruby" (lang/key->str :block/mymod.ruby)))
  (is (= "death.mymod" (lang/key->str :death/mymod))))

(deftest lang-generate-paths-and-data
  (let [{:keys [files]} (lang/generate "mymod" {:en_us {"item.mymod.ruby" "Ruby"}
                                                :ru_ru {:item/mymod.ruby "Рубин"}})]
    (is (= #{"assets/mymod/lang/en_us.json" "assets/mymod/lang/ru_ru.json"}
           (set (map :path files))))
    (is (= {"item.mymod.ruby" "Ruby"} (:data (by-path files "assets/mymod/lang/en_us.json"))))
    (is (= {"item.mymod.ruby" "Рубин"} (:data (by-path files "assets/mymod/lang/ru_ru.json"))))))

;;; Tags

(deftest tags-generate-paths-values-refs
  (let [{:keys [files refs]} (tags/generate {:block {"minecraft:mineable/pickaxe" [:mymod/ruby_ore]
                                                     :mymod/gems [:mymod/ruby "#minecraft:gem_tag"]}})]
    (is (= {:values ["mymod:ruby_ore"]}
           (:data (by-path files "data/minecraft/tags/block/mineable/pickaxe.json"))))
    (is (= {:values ["mymod:ruby" "#minecraft:gem_tag"]}
           (:data (by-path files "data/mymod/tags/block/gems.json"))))
    (testing "only non-tag members become registry refs"
      (is (= #{"mymod:ruby_ore" "mymod:ruby"} (set (map :id refs))))
      (is (every? #(= :block (:registry %)) refs)))))

;;; Recipes

(deftest recipe-shaped
  (let [{:keys [files refs]} (recipe/generate "mymod"
                                              {:mymod/ruby_block {:type :shaped :category :building
                                                                  :pattern ["###" "###" "###"]
                                                                  :key {\# :mymod/ruby}
                                                                  :result :mymod/ruby_block}})
        data (:data (by-path files "data/mymod/recipe/ruby_block.json"))]
    (is (= "minecraft:crafting_shaped" (:type data)))
    (is (= "building" (:category data)))
    (is (= {"#" "mymod:ruby"} (:key data)))
    (is (= ["###" "###" "###"] (:pattern data)))
    (is (= {:id "mymod:ruby_block"} (:result data)))
    (is (= #{"mymod:ruby" "mymod:ruby_block"} (set (map :id refs))))
    (is (every? #(= :item (:registry %)) refs))))

(deftest recipe-shapeless-with-count
  (let [{:keys [files]} (recipe/generate "mymod"
                                         {:mymod/ruby {:type :shapeless
                                                       :ingredients [:mymod/ruby_block]
                                                       :result {:id :mymod/ruby :count 9}}})
        data (:data (by-path files "data/mymod/recipe/ruby.json"))]
    (is (= "minecraft:crafting_shapeless" (:type data)))
    (is (= ["mymod:ruby_block"] (:ingredients data)))
    (is (= {:id "mymod:ruby" :count 9} (:result data)))))

(deftest recipe-cooking-and-stonecutting
  (let [{:keys [files]} (recipe/generate "mymod"
                                         {:mymod/smelt {:type :smelting :ingredient :mymod/ruby_ore
                                                        :result :mymod/ruby :experience 0.7 :cookingtime 200}
                                          :mymod/cut {:type :stonecutting :ingredient :mymod/ruby_block
                                                      :result {:id :mymod/ruby :count 9}}})
        smelt (:data (by-path files "data/mymod/recipe/smelt.json"))
        cut (:data (by-path files "data/mymod/recipe/cut.json"))]
    (is (= "minecraft:smelting" (:type smelt)))
    (is (= "mymod:ruby_ore" (:ingredient smelt)))
    (is (= 0.7 (:experience smelt)))
    (is (= 200 (:cookingtime smelt)))
    (is (= "minecraft:stonecutting" (:type cut)))
    (is (= {:id "mymod:ruby" :count 9} (:result cut)))))

(deftest recipe-tag-ingredient-not-a-ref
  (let [{:keys [refs]} (recipe/generate "mymod"
                                        {:mymod/x {:type :shapeless
                                                   :ingredients ["#minecraft:planks"]
                                                   :result :mymod/ruby}})]
    (is (= ["mymod:ruby"] (map :id refs)))))

(deftest recipe-raw-passthrough
  (let [{:keys [files refs]} (recipe/generate "mymod"
                                              {:mymod/x {:type :raw :json {:type "minecraft:special" :foo 1}}})]
    (is (= {:type "minecraft:special" :foo 1} (:data (by-path files "data/mymod/recipe/x.json"))))
    (is (empty? refs))))

;;; Loot

(deftest loot-self-drop-and-explicit-drop
  (let [{:keys [files refs]} (loot/generate "mymod" {:mymod/ruby_block {:type :block}
                                                     :mymod/ruby_ore {:type :block :drop :mymod/ruby}})
        self (:data (by-path files "data/mymod/loot_table/ruby_block.json"))
        ore (:data (by-path files "data/mymod/loot_table/ruby_ore.json"))]
    (is (= "minecraft:block" (:type self)))
    (is (= "mymod:ruby_block" (-> self :pools first :entries first :name)))
    (is (= "mymod:ruby" (-> ore :pools first :entries first :name)))
    (is (= #{"mymod:ruby_block" "mymod:ruby"} (set (map :id refs))))))

(deftest loot-self-false-drops-nothing
  (let [{:keys [files refs]} (loot/generate "mymod" {:mymod/x {:type :block :self false}})]
    (is (= [] (:pools (:data (by-path files "data/mymod/loot_table/x.json")))))
    (is (empty? refs))))

;;; Advancements

(deftest advancement-raw
  (let [{:keys [files]} (advancement/generate "mymod" {:mymod/root {:type :raw :json {:display {:title "x"}}}})]
    (is (= {:display {:title "x"}} (:data (by-path files "data/mymod/advancement/root.json"))))))
