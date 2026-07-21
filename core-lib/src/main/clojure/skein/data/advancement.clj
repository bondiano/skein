(ns skein.data.advancement
  "Advancements as data -> `data/<modid>/advancement/<name>.json`.

      {:mymod/root {:type :raw :json {...advancement JSON...}}}

  The advancement JSON is broad and still evolving upstream, so this
  generator ships the `:raw` escape hatch only: a verbatim `:json` body,
  written under the mod's advancement folder. Sugar for the common criteria/
  rewards shape can grow on top later without changing the file layout.

  Pure: `generate` returns `{:files :refs}`; ids inside a raw body are the
  author's responsibility, so there are no registry refs to check."
  (:require [skein.data.ids :as ids]))

(defn generate
  "Build the advancement files from the `:advancements` section
  (`{advancement-id {:type :raw :json {...}}}`)."
  [modid advancements-section]
  {:files
   (mapv (fn [[adv-id {:keys [json]}]]
           (let [[ans aname] (ids/parts adv-id)
                 ns (if (= "minecraft" ans) modid ans)]
             {:path (str "data/" ns "/advancement/" aname ".json")
              :data json}))
         advancements-section)
   :refs []})
