(ns skein.data.tags
  "Tags as data -> `data/<tag-ns>/tags/<registry>/<tag-path>.json`.

      {:block {:minecraft/mineable/pickaxe [:mymod/ruby_ore]
               :mymod/gems               [:mymod/ruby]}}

  A tag file lives under the *tag's own* namespace (a vanilla tag like
  `minecraft:mineable/pickaxe` is extended under `data/minecraft/...`, so a
  mod adding to it writes there); the registry keyword (`:block`, `:item`,
  `:enchantment`, or a namespaced one like `:worldgen/biome`) picks the
  `tags/<registry>` folder.

  Members are ids (validated against that registry at build) or `\"#tag\"`
  references to other tags (passed through, not registry-checked). Pure:
  `generate` returns `{:files :refs}`; the refs are the member ids to verify."
  (:require [skein.data.ids :as ids]))

(defn- registry-path
  "The `tags/<...>` sub-path for a registry keyword: `:block` -> `\"block\"`,
  `:worldgen/biome` -> `\"worldgen/biome\"`."
  ^String [registry]
  (let [ns (namespace registry)]
    (str (when ns (str ns "/")) (name registry))))

(defn- tag-file
  "The relative resource path for a tag id under a registry."
  ^String [registry tag-id]
  (let [[tag-ns tag-name] (ids/parts tag-id)]
    (str "data/" tag-ns "/tags/" (registry-path registry) "/" tag-name ".json")))

(defn- member-json [member]
  ;; A plain id normalizes to "ns:path"; a "#tag" reference stays a reference.
  (ids/normalize member))

(defn generate
  "Build the tag files from the `:tags` section (`{registry {tag [members]}}`)."
  [tags-section]
  (let [entries (for [[registry tag-map] tags-section
                      [tag-id members] tag-map]
                  (let [values (mapv member-json members)]
                    {:file {:path (tag-file registry tag-id)
                            :data {:values values}}
                     :refs (for [m members
                                 :when (not (ids/tag-ref? m))]
                             {:registry registry
                              :id (ids/id-str m)
                              :where (str "tag " tag-id)})}))]
    {:files (mapv :file entries)
     :refs (into [] (mapcat :refs) entries)}))
