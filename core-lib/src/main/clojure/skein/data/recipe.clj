(ns skein.data.recipe
  "Recipes as data -> `data/<modid>/recipe/<name>.json`.

      {:mymod/ruby_block
       {:type :shaped :category :building
        :pattern [\"###\" \"###\" \"###\"]
        :key {\\# :mymod/ruby}
        :result :mymod/ruby_block}

       :mymod/ruby_from_block
       {:type :shapeless :ingredients [:mymod/ruby_block]
        :result {:id :mymod/ruby :count 9}}}

  Supported `:type`s: `:shaped`, `:shapeless`, the four cooking recipes
  (`:smelting :blasting :smoking :campfire`), `:stonecutting`, and `:raw`
  (a verbatim `:json` body escape hatch). An ingredient is an id, a
  `\"#tag\"` reference, or a vector of ids (a choice of any). Ingredient and
  result ids are collected as item-registry refs for the build id check.

  Pure: `generate` returns `{:files :refs}`."
  (:require [skein.data.ids :as ids]))

(def ^:private vanilla-type
  {:shaped "minecraft:crafting_shaped"
   :shapeless "minecraft:crafting_shapeless"
   :smelting "minecraft:smelting"
   :blasting "minecraft:blasting"
   :smoking "minecraft:smoking"
   :campfire "minecraft:campfire_cooking"
   :stonecutting "minecraft:stonecutting"})

(defn- label-str [v] (if (keyword? v) (name v) v))

(defn- ingredient-json
  "An ingredient -> its JSON form and the item ids it references."
  [ingredient]
  (if (vector? ingredient)
    {:json (mapv ids/normalize ingredient)
     :ids (mapv ids/id-str (remove ids/tag-ref? ingredient))}
    {:json (ids/normalize ingredient)
     :ids (if (ids/tag-ref? ingredient) [] [(ids/id-str ingredient)])}))

(defn- result-json
  "A result (id or `{:id :count}`) -> `{:id .. :count ..}` and its item id."
  [result]
  (if (map? result)
    {:json (cond-> {:id (ids/id-str (:id result))}
             (:count result) (assoc :count (:count result)))
     :ids [(ids/id-str (:id result))]}
    {:json {:id (ids/id-str result)}
     :ids [(ids/id-str result)]}))

(defn- optional [m k v] (cond-> m (some? v) (assoc k v)))

(defmulti ^:private recipe-json
  "Build the JSON data map for a recipe and collect the item ids it uses:
  `{:data <json> :ids [id ...]}`."
  :type)

(defmethod recipe-json :shaped [{:keys [key pattern result category group]}]
  (let [keyed (map (fn [[k ing]] [(str (if (keyword? k) (name k) k)) (ingredient-json ing)]) key)
        r (result-json result)]
    {:data (-> {:type (vanilla-type :shaped)
                :pattern pattern
                :key (into {} (map (fn [[k v]] [k (:json v)])) keyed)
                :result (:json r)}
               (optional :category (label-str category))
               (optional :group (label-str group)))
     :ids (into (:ids r) (mapcat (comp :ids second)) keyed)}))

(defmethod recipe-json :shapeless [{:keys [ingredients result category group]}]
  (let [ings (map ingredient-json ingredients)
        r (result-json result)]
    {:data (-> {:type (vanilla-type :shapeless)
                :ingredients (mapv :json ings)
                :result (:json r)}
               (optional :category (label-str category))
               (optional :group (label-str group)))
     :ids (into (:ids r) (mapcat :ids) ings)}))

(defn- cooking-json [{:keys [type ingredient result experience cookingtime category group]}]
  (let [ing (ingredient-json ingredient)
        r (result-json result)]
    {:data (-> {:type (vanilla-type type)
                :ingredient (:json ing)
                :result (:json r)}
               (optional :experience experience)
               (optional :cookingtime cookingtime)
               (optional :category (label-str category))
               (optional :group (label-str group)))
     :ids (into (:ids r) (:ids ing))}))

(defmethod recipe-json :smelting [recipe] (cooking-json recipe))
(defmethod recipe-json :blasting [recipe] (cooking-json recipe))
(defmethod recipe-json :smoking  [recipe] (cooking-json recipe))
(defmethod recipe-json :campfire [recipe] (cooking-json recipe))

(defmethod recipe-json :stonecutting [{:keys [ingredient result group]}]
  (let [ing (ingredient-json ingredient)
        r (result-json result)]
    {:data (-> {:type (vanilla-type :stonecutting)
                :ingredient (:json ing)
                :result (:json r)}
               (optional :group (label-str group)))
     :ids (into (:ids r) (:ids ing))}))

(defmethod recipe-json :raw [{:keys [json]}]
  {:data json :ids []})

(defn generate
  "Build the recipe files from the `:recipes` section (`{recipe-id recipe}`)."
  [modid recipes-section]
  (let [entries (for [[recipe-id recipe] recipes-section]
                  (let [[rns rname] (ids/parts recipe-id)
                        ns (if (= "minecraft" rns) modid rns)
                        {:keys [data ids]} (recipe-json recipe)]
                    {:file {:path (str "data/" ns "/recipe/" rname ".json")
                            :data data}
                     :refs (map (fn [id] {:registry :item :id id
                                          :where (str "recipe " recipe-id)}) ids)}))]
    {:files (mapv :file entries)
     :refs (into [] (mapcat :refs) entries)}))
