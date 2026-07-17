(ns skein.mixin.aot
  "The build entry point the Skein Gradle plugin runs in the forked AOT
  JVM (clojure.main -m skein.mixin.aot ns1 ns2 ...) instead of
  clojure.lang.Compile. One JVM does both jobs on purpose: AOT
  compilation loads the mod's namespaces, loading executes `defmixin`
  forms, and those register their resolved declarations in
  skein.mixin's registry — which this pipeline then drains. Afterwards:

  1. collect declarations: the defmixin registry + mixins.edn from the
     mod's resource roots (validated against the malli schema and
     resolved against the compile classpath — the real game classes);
  2. generate the mixin classes (ASM) into the compile path, next to
     the AOT output, so they ride into the jar and onto the dev
     classpath;
  3. write the <modid>.skein-mixins.json mixin config into the compile
     path root (the plugin registers it in fabric.mod.json; no refmap —
     nothing is remapped in MC 26.x).

  Configuration comes in as system properties, mirroring
  clojure.lang.Compile: clojure.compile.path,
  clojure.compile.warn-on-reflection, plus skein.mixin.modid and
  skein.mixin.resources (path-separated resource roots)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)))

;; CRITICAL: this namespace must not require any other skein.* namespace
;; at load time. It loads *before* (compile ...) runs — anything it pulls
;; in would count as "already loaded" during the mod's AOT compilation
;; and would silently NOT be compiled into the mod jar, breaking the
;; production boot (mods ship classes only; core-lib sources are not on
;; the production classpath). Everything skein.* resolves lazily, after
;; compilation, via requiring-resolve.

(defn- fail [msg data]
  (throw (ex-info msg (assoc data :skein/mixin-error true))))

;;; AOT compilation (what clojure.lang.Compile does, minus the wrapper)

(defn- compile-namespaces! [compile-path warn? namespaces]
  (binding [*compile-path* compile-path
            *warn-on-reflection* warn?]
    (doseq [n namespaces]
      (compile (symbol n)))))

;;; mixins.edn collection

(defn- validate-mixins-file
  "Malli validation via requiring-resolve: the schema (and malli) are
  on the classpath only when the plugin saw a mixins.edn."
  [data source]
  (let [validate! (try
                    (requiring-resolve 'skein.mixin.schema/validate!)
                    (catch Exception e
                      (fail (str "Cannot validate " source ": malli is not on the compile"
                                 " classpath. The Skein Gradle plugin adds it automatically"
                                 " when a mixins.edn exists; running the pipeline by hand"
                                 " requires adding metosin/malli yourself.")
                            {:source source :cause (str e)})))]
    (validate! data source)))

(defn- read-mixins-file [^File file]
  (let [data (try
               (edn/read-string (slurp file))
               (catch Exception e
                 (fail (str "Cannot read " file " as EDN: " (.getMessage e)) {:file (str file)})))]
    (:mixins (validate-mixins-file data (str file)))))

(defn- assign-default-classes
  "EDN declarations may omit :class; the default is
  <modid>.skein_mixins.<TargetSimpleName>Mixin. Two declarations for
  the same target then collide — that asks for explicit :class names."
  [declarations modid]
  (mapv (fn [{:keys [target class] :as declaration}]
          (if class
            declaration
            (let [simple (last (str/split (str target) #"\."))]
              (assoc declaration :class
                     (symbol (str (munge modid) ".skein_mixins." simple "Mixin"))))))
        declarations))

(defn- collect-edn-declarations [resource-dirs modid]
  (let [authored (->> resource-dirs
                      (map #(io/file % "mixins.edn"))
                      (filter #(.isFile ^File %))
                      (mapcat read-mixins-file)
                      vec)]
    (when (seq authored)
      (let [resolve-declaration (requiring-resolve 'skein.mixin.resolve/resolve-declaration)]
        (mapv resolve-declaration (assign-default-classes authored modid))))))

;;; Output

(defn- config-package
  "The one package all generated classes live in. The mixin config's
  package is excluded from normal classloading, so it must be dedicated
  — which also means it must be shared."
  [declarations]
  (let [package-of #(str/join "." (butlast (str/split (:class %) #"\.")))
        packages (distinct (map package-of declarations))]
    (when (some str/blank? packages)
      (fail (str "Generated mixin classes must live in a package (got a default-package"
                 " :class). Use <modid>.skein_mixins.<Name>.")
            {:classes (mapv :class declarations)}))
    (when (next packages)
      (fail (str "All generated mixin classes must share one package — the mixin config's"
                 " package is excluded from normal classloading, so Skein dedicates a single"
                 " one to generated classes. Got: " (str/join ", " (sort packages))
                 ". Align the :class declarations (default: <modid>.skein_mixins.<Name>).")
            {:packages (vec packages)}))
    (first packages)))

(defn- write-mixin-config! [modid declarations compile-path]
  (let [package (config-package declarations)
        simple-names (->> declarations
                          (map :class)
                          sort
                          (map #(subs % (inc (count package)))))
        config-name (str modid ".skein-mixins.json")
        ;; Hand-formatted: fixed keys, interpolated names only — no JSON
        ;; library in the compile fork.
        json (str "{\n"
                  "\t\"required\": true,\n"
                  "\t\"minVersion\": \"0.8\",\n"
                  "\t\"package\": \"" package "\",\n"
                  "\t\"compatibilityLevel\": \"JAVA_25\",\n"
                  "\t\"mixins\": [\n"
                  (str/join ",\n" (map #(str "\t\t\"" % "\"") simple-names))
                  "\n\t],\n"
                  "\t\"injectors\": {\n"
                  "\t\t\"defaultRequire\": 1\n"
                  "\t}\n"
                  "}\n")]
    (spit (io/file compile-path config-name) json)
    config-name))

(defn- registry-declarations
  "Declarations defmixin forms registered while the compile loaded the
  mod's namespaces. Resolved lazily: if skein.mixin never loaded, no
  defmixin ran and the registry is empty by definition."
  []
  (if (find-ns 'skein.mixin)
    ((requiring-resolve 'skein.mixin/declarations))
    []))

(defn- generate! [{:keys [modid resource-dirs compile-path]}]
  (let [edn-declarations (collect-edn-declarations resource-dirs (or modid "mod"))
        declarations (into (vec (registry-declarations)) edn-declarations)
        duplicates (->> declarations (map :class) frequencies (filter #(< 1 (val %))) (map key))]
    (when (seq declarations)
      (when (seq duplicates)
        (fail (str "Duplicate generated mixin class name(s): " (str/join ", " duplicates)
                   ". Each declaration (defmixin or mixins.edn) needs its own :class.")
              {:duplicates (vec duplicates)}))
      (when (str/blank? modid)
        (fail (str "Mixin declarations found, but no mod id: the mixin config file is named"
                   " <modid>.skein-mixins.json. Add an \"id\" to fabric.mod.json or set"
                   " skein.modId in the build script.")
              {}))
      (let [write-classes! (try
                             (requiring-resolve 'skein.mixin.codegen/write-classes!)
                             (catch Exception e
                               (fail (str "Cannot generate mixin classes: org.ow2.asm is not on"
                                          " the compile classpath (Fabric dev classpaths always"
                                          " carry it via the loader).")
                                     {:cause (str e)})))
            class-names (write-classes! declarations (io/file compile-path))
            config-name (write-mixin-config! modid declarations compile-path)]
        (println (str "Skein: generated " (count class-names) " mixin class(es) into "
                      config-name " — " (str/join ", " class-names)))))))

;;; Entry point

(defn- mixin-error
  "The first :skein/mixin-error in the cause chain (defmixin failures
  arrive wrapped in a CompilerException), or nil."
  [^Throwable t]
  (->> (iterate #(.getCause ^Throwable %) t)
       (take-while some?)
       (filter #(and (instance? clojure.lang.ExceptionInfo %)
                     (:skein/mixin-error (ex-data %))))
       first))

(defn -main [& namespaces]
  (try
    (let [compile-path (System/getProperty "clojure.compile.path")
          warn? (Boolean/parseBoolean
                 (System/getProperty "clojure.compile.warn-on-reflection" "true"))
          modid (System/getProperty "skein.mixin.modid")
          resource-dirs (some-> (System/getProperty "skein.mixin.resources")
                                (str/split (re-pattern (java.util.regex.Pattern/quote
                                                        File/pathSeparator))))]
      (when (str/blank? compile-path)
        (fail "skein.mixin.aot needs -Dclojure.compile.path=<classes dir>" {}))
      (compile-namespaces! compile-path warn? namespaces)
      (generate! {:modid modid :resource-dirs resource-dirs :compile-path compile-path})
      (shutdown-agents))
    (catch Throwable t
      (if-let [e (mixin-error t)]
        (do (binding [*out* *err*]
              (println)
              (println (str "Skein mixin error: " (.getMessage ^Throwable e)))
              (when-not (identical? e t)
                (println (str "  at: " (.getMessage t)))))
            (System/exit 1))
        (throw t)))))
