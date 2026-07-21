(ns skein.data.lang
  "Translations as data -> `assets/<modid>/lang/<locale>.json`.

  A key is written verbatim as a string (`\"block.mymod.ruby\"`) or as a
  keyword for convenience (`:block/mymod/ruby`, where the namespace and the
  `/`-separated path join with `.` -> `\"block.mymod.ruby\"`). Values are the
  translated strings.

  Pure: `generate` returns `{:files [{:path :data}]}` for the datagen writer;
  lang keys are translation keys, not registry ids, so there is nothing to
  validate against the registries here (a soft content-key cross-check lives
  in the datagen orchestrator, which knows the mod's declared content)."
  (:require [clojure.string :as str]))

(defn key->str
  "A lang key as its dotted string form. A string passes through; a keyword
  joins its namespace and `/`-separated name with dots
  (`:block/mymod/ruby` -> `\"block.mymod.ruby\"`)."
  ^String [k]
  (cond
    (string? k) k
    (keyword? k) (let [ns (namespace k)
                       nm (str/replace (name k) "/" ".")]
                   (if ns (str ns "." nm) nm))
    :else (throw (ex-info (str "A lang key must be a string or keyword, got " (pr-str k))
                          {:key k}))))

(defn- locale-name ^String [locale]
  (cond
    (keyword? locale) (name locale)
    (string? locale) locale
    :else (throw (ex-info (str "A locale must be a keyword (:en_us) or string, got " (pr-str locale))
                          {:locale locale}))))

(defn generate
  "Build the lang files for `modid` from the `:lang` section
  (`{locale {key value}}`)."
  [modid lang-section]
  {:files
   (mapv (fn [[locale translations]]
           {:path (str "assets/" modid "/lang/" (locale-name locale) ".json")
            :data (into {} (map (fn [[k v]] [(key->str k) v])) translations)})
         lang-section)
   :refs []})
