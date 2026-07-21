(ns skein.data.datagen
  "The build entry point the Skein Gradle plugin runs in a forked JVM
  (`clojure.main -m skein.data.datagen ns1 ns2 ...`) to turn a mod's
  `skein-data.edn` into generated resources before `jar`:

  1. read and merge `skein-data.edn` from the mod's resource roots;
  2. validate it against the Malli schema (shape errors fail the build with
     the offending path);
  3. bootstrap vanilla and load the mod's namespaces under
     `skein.core/*collect-only*` so `register!` records the mod's own content
     ids without touching the game;
  4. check every id a tag/recipe/loot table references against the real
     registries (vanilla) or the mod's declared content — an unknown id fails
     the build with a \"did you mean\" list, the headline content-mod safety;
  5. emit the JSON files (lang/tags/recipes/loot/advancements) into the
     output directory the plugin packs into the jar.

  Configuration arrives as system properties, mirroring the mixin pipeline:
  `skein.data.modid`, `skein.data.resources` (path-separated roots),
  `skein.data.out` (output directory). Runs in its own fork — unlike the
  mixin AOT entry point it may `require` skein.* freely (no compilation
  happens here to exclude them from)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.core :as core]
            [skein.data.advancement :as advancement]
            [skein.data.ids :as ids]
            [skein.data.lang :as lang]
            [skein.data.loot :as loot]
            [skein.data.recipe :as recipe]
            [skein.data.schema :as schema]
            [skein.data.tags :as tags]
            [skein.schema :as sk-schema])
  (:import (java.io File)
           (net.minecraft SharedConstants)
           (net.minecraft.core.registries BuiltInRegistries)
           (net.minecraft.server Bootstrap)))

(def ^:const data-file "skein-data.edn")

(defn- fail [msg data]
  (throw (ex-info msg (assoc data :skein/data-error true))))

;;; Reading the declaration

(defn- read-data-files [resource-dirs]
  (let [files (->> resource-dirs
                   (map #(io/file % data-file))
                   (filter #(.isFile ^File %)))]
    (reduce
     (fn [acc ^File file]
       (let [data (try
                    (edn/read-string (slurp file))
                    (catch Exception e
                      (fail (str "Cannot read " file " as EDN: " (.getMessage e)) {:file (str file)})))]
         (when-not (map? data)
           (fail (str data-file " must be a map, got " (pr-str (type data)) " in " file) {:file (str file)}))
         (merge-with merge acc data)))
     {}
     files)))

;;; Registry snapshots — the valid ids per registry, after bootstrap.

(defn- registry-ids [^net.minecraft.core.Registry registry]
  (into #{} (map str) (.keySet registry)))

(def ^:private registries
  "The registry keywords the id check knows how to verify, to their built-in
  registry. A tag/recipe reference into another registry is passed through
  unchecked (dynamic/datapack registries are not populated at build)."
  (delay {:block BuiltInRegistries/BLOCK
          :item BuiltInRegistries/ITEM}))

;;; Loading the mod's own content declarations.

(defn- collect-declared! [namespaces]
  (binding [core/*collect-only* true]
    (doseq [n namespaces]
      (try
        (require (symbol n))
        (catch Exception e
          (fail (str "Cannot load namespace " n " to collect its content declarations: "
                     (.getMessage e))
                {:namespace n})))))
  (core/declared-content))

;;; Id validation

(defn- valid-ids
  "The set of valid ids for a registry: the built-in registry keys plus the
  mod's declared content of that kind."
  [registry declared]
  (let [built-in (some-> (@registries registry) registry-ids)
        own (case registry
              :block (:blocks declared)
              :item (:items declared)
              nil)]
    (when (or built-in own)
      ;; declared ids are keywords; registry keys are already "ns:path" strings.
      (into (or built-in #{}) (map ids/id-str) own))))

(defn- check-refs!
  "Fails the build listing every referenced id that is not registered, each
  with a nearest-name suggestion. Refs into a registry with no build-time
  snapshot are skipped; a mod-owned id is skipped when the mod declared no
  content (its ids cannot be known then)."
  [refs declared modid]
  (let [declared-empty? (and (empty? (:blocks declared)) (empty? (:items declared)))
        problems
        (for [{:keys [registry id where]} refs
              :let [valid (valid-ids registry declared)]
              :when valid
              :when (not (contains? valid id))
              ;; A mod-owned id we cannot know (no top-level content declared)
              ;; is not flagged — only a genuinely resolvable typo is.
              :when (not (and declared-empty?
                              (= modid (first (ids/parts id)))))]
          (str "  " id " (in " where ") is not a registered "
               (name registry) (ids/did-you-mean id valid)))]
    (when (seq problems)
      (fail (str "Unknown id(s) in " data-file ":\n" (str/join "\n" (distinct problems))
                 "\n\nIds must be registered content — vanilla, another mod's, or your own"
                 " (declared with register! at the top level so the build can see it).")
            {:count (count problems)}))))

;;; Soft lang cross-check — a warning, not a failure (many valid lang keys are
;;; not content ids: creative-tab names, subtitles, custom-name keys).

(defn- lang-content-warnings [lang-section declared]
  (let [own (into (:blocks declared) (:items declared))
        content-key->id (fn [k]
                          (let [s (lang/key->str k)
                                m (re-matches #"(?:block|item)\.([^.]+)\.(.+)" s)]
                            (when m (keyword (nth m 1) (str/replace (nth m 2) "." "/")))))]
    (for [[_ translations] lang-section
          [k _] translations
          :let [id (content-key->id k)]
          :when (and id (seq own) (not (contains? own id)))]
      (str "  lang key " (pr-str (lang/key->str k)) " looks like content " id
           " but no such block/item is declared"))))

;;; Generation and writing

(defn- generate-all [modid data]
  (let [sections [(when-some [s (:lang data)] (lang/generate modid s))
                  (when-some [s (:tags data)] (tags/generate s))
                  (when-some [s (:recipes data)] (recipe/generate modid s))
                  (when-some [s (:loot data)] (loot/generate modid s))
                  (when-some [s (:advancements data)] (advancement/generate modid s))]
        present (remove nil? sections)]
    {:files (into [] (mapcat :files) present)
     :refs (into [] (mapcat :refs) present)}))

(defn- write-files! [out-dir files]
  (let [json (requiring-resolve 'skein.data.json/write)]
    (doseq [{:keys [path data]} files]
      (let [target (io/file out-dir path)]
        (io/make-parents target)
        (spit target (json data))))
    (count files)))

;;; Entry point

(defn- data-error [^Throwable t]
  (->> (iterate #(.getCause ^Throwable %) t)
       (take-while some?)
       (filter #(and (instance? clojure.lang.ExceptionInfo %)
                     (:skein/data-error (ex-data %))))
       first))

(defn run
  "The datagen pipeline, split from -main for testing. Returns the number of
  files written."
  [{:keys [modid resource-dirs out-dir namespaces]}]
  (let [data (read-data-files resource-dirs)]
    (if (empty? data)
      0
      (do
        (sk-schema/validate! schema/data data data-file)
        (SharedConstants/tryDetectVersion)
        (Bootstrap/bootStrap)
        (let [declared (collect-declared! namespaces)
              {:keys [files refs]} (generate-all (or modid "mod") data)]
          (check-refs! refs declared modid)
          (doseq [w (lang-content-warnings (:lang data) declared)]
            (binding [*out* *err*] (println (str "Skein datagen warning:\n" w))))
          (let [n (write-files! out-dir files)]
            (println (str "Skein: generated " n " resource file(s) from " data-file
                          " into " out-dir))
            n))))))

(defn -main [& namespaces]
  (try
    (let [modid (System/getProperty "skein.data.modid")
          out-dir (System/getProperty "skein.data.out")
          resource-dirs (some-> (System/getProperty "skein.data.resources")
                                (str/split (re-pattern (java.util.regex.Pattern/quote File/pathSeparator))))]
      (when (str/blank? out-dir)
        (fail "skein.data.datagen needs -Dskein.data.out=<output dir>" {}))
      (run {:modid modid
            :resource-dirs (or resource-dirs [])
            :out-dir out-dir
            :namespaces namespaces})
      (shutdown-agents))
    (catch Throwable t
      (if-let [e (data-error t)]
        (do (binding [*out* *err*]
              (println)
              (println (str "Skein datagen error: " (.getMessage ^Throwable e))))
            (System/exit 1))
        (throw t)))))
