(ns skein.data.loot
  "Loot tables as data -> `data/<modid>/loot_table/<name>.json`.

      {:mymod/ruby_block {:type :block}                 ; self-drop
       :mymod/ruby_ore   {:type :block :drop :mymod/ruby}}

  A `:block` table drops the block itself by default (a single guaranteed
  item that survives explosions, like a vanilla building block); `:drop`
  names a different item; `:self false` with no `:drop` yields an empty
  table (the block drops nothing). `:raw` passes a verbatim `:json` body
  through for anything richer (fortune bonuses, alternatives, chest loot).

  The loot-id is the block id for a block table. Pure: `generate` returns
  `{:files :refs}`; the dropped item id is an item-registry ref."
  (:require [skein.data.ids :as ids]))

(defmulti ^:private loot-json
  "Build the JSON data and collect referenced item ids for a loot table:
  `{:data <json> :ids [id ...]}`. `loot-id` is the table's own id."
  (fn [_loot-id table] (:type table)))

(defmethod loot-json :block [loot-id {:keys [drop self]}]
  (let [self? (if (some? self) self (nil? drop))
        item (cond drop (ids/id-str drop)
                   self? (ids/id-str loot-id)
                   :else nil)]
    {:data {:type "minecraft:block"
            :pools (if item
                     [{:rolls 1
                       :entries [{:type "minecraft:item" :name item}]
                       :conditions [{:condition "minecraft:survives_explosion"}]}]
                     [])}
     :ids (if item [item] [])}))

(defmethod loot-json :raw [_ {:keys [json]}]
  {:data json :ids []})

(defn generate
  "Build the loot-table files from the `:loot` section (`{loot-id table}`)."
  [modid loot-section]
  (let [entries (for [[loot-id table] loot-section]
                  (let [[lns lname] (ids/parts loot-id)
                        ns (if (= "minecraft" lns) modid lns)
                        {:keys [data ids]} (loot-json loot-id table)]
                    {:file {:path (str "data/" ns "/loot_table/" lname ".json")
                            :data data}
                     :refs (map (fn [id] {:registry :item :id id
                                          :where (str "loot table " loot-id)}) ids)}))]
    {:files (mapv :file entries)
     :refs (into [] (mapcat :refs) entries)}))
