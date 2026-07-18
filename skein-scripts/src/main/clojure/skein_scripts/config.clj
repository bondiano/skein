(ns skein-scripts.config
  "Reads and normalizes the Skein Scripts settings from
  `config/skein/scripts.properties`. Pure over a properties map so it is
  testable without a running game — `core` supplies the real config dir
  and file.

  Keys (all optional):
  - `phase`   = server-started (default) | server-starting | mod-init
                when the scripts are loaded the first time
  - `watch`   = false (default) | true — a file watcher reloads on change
  - `offline` = false (default) | true — deps.edn resolution never hits the
                network, only the local `repo` cache
  - `scripts-dir` — override for the scripts directory
                (default `<config>/skein/scripts`)
  - `repo-dir`    — override for the deps.edn cache
                (default `<config>/skein/repo`)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.util Properties)))

(def phases
  "Load phases, in the order they occur during startup."
  #{:mod-init :server-starting :server-started})

(def ^:private defaults
  {:phase :server-started
   :watch? false
   :offline? false})

(defn- parse-phase [s]
  (let [k (some-> s str/trim not-empty (str/replace "_" "-") keyword)]
    (cond
      (nil? k) (:phase defaults)
      (contains? phases k) k
      :else (throw (ex-info (str "Unknown Skein Scripts phase " (pr-str s)
                                 " — expected one of " (vec (sort phases)))
                            {:value s :supported phases})))))

(defn- parse-bool [s default]
  (if-let [v (some-> s str/trim not-empty)]
    (contains? #{"true" "yes" "on" "1"} (str/lower-case v))
    default))

(defn parse
  "Builds the config map from a base directory (a File/Path/string, the
  standard Skein config root, typically `config/skein`) and a properties
  map (nil = all defaults). Resolves `scripts-dir` and `repo-dir` to
  absolute Files."
  [^File base-dir props]
  (let [get* (fn [k] (when props (get props k)))
        base (io/file base-dir)]
    {:phase (parse-phase (get* "phase"))
     :watch? (parse-bool (get* "watch") (:watch? defaults))
     :offline? (parse-bool (get* "offline") (:offline? defaults))
     :scripts-dir (if-let [d (get* "scripts-dir")]
                    (io/file d)
                    (io/file base "scripts"))
     :repo-dir (if-let [d (get* "repo-dir")]
                 (io/file d)
                 (io/file base "repo"))
     ;; `<config>/skein/deps.edn` — a tools.deps map ({:deps {...}
     ;; :mvn/repos {...}}); optional.
     :deps-file (if-let [f (get* "deps-file")]
                  (io/file f)
                  (io/file base "deps.edn"))}))

(defn properties->map
  "A Properties instance (or nil) as a plain string→string map."
  [^Properties props]
  (when props
    (into {} (map (fn [k] [k (.getProperty props k)])) (.stringPropertyNames props))))

(defn load-file-props
  "Loads a .properties File into a map, or nil when the file is absent.
  Throws (with the path) when the file exists but cannot be read — a
  present-but-broken config must not be silently ignored."
  [^File file]
  (when (.isFile file)
    (let [props (Properties.)]
      (with-open [in (io/input-stream file)]
        (.load props in))
      (properties->map props))))
