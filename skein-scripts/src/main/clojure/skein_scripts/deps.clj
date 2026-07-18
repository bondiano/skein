(ns skein-scripts.deps
  "Optional `deps.edn` support for scripts: resolves the declared libraries
  with tools.deps and adds their jars to the scripts classloader, so a
  script can `(require ...)` a Maven/Clojars dependency.

  The resolver cache is redirected to a project-local `repo` directory
  (default `config/skein/repo`) so a server's dependencies stay next to its
  config and can be pre-populated for offline use.

  tools.deps ships inside this mod (JiJ) — unlike ordinary mods, a scripting
  host needs a runtime resolver. It is still resolved via `requiring-resolve`
  so a stripped build without it degrades to a clear error rather than a
  load-time failure."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (clojure.lang DynamicClassLoader)
           (java.io File)))

(defn- read-deps-edn
  "Parses the deps.edn File into a deps map, or nil when it is absent."
  [^File deps-file]
  (when (and deps-file (.isFile deps-file))
    (edn/read-string (slurp deps-file))))

(def ^:private fallback-repos
  {"central" {:url "https://repo1.maven.org/maven2/"}
   "clojars" {:url "https://repo.clojars.org/"}})

(defn- resolve-deps-fn []
  (or (try (requiring-resolve 'clojure.tools.deps/resolve-deps)
           (catch java.io.FileNotFoundException _ nil))
      (throw (ex-info (str "deps.edn present but tools.deps is not on the classpath — "
                           "cannot resolve dependencies. This build of Skein Scripts "
                           "should bundle it; a stripped build cannot use deps.edn.")
                      {}))))

(defn resolve-into!
  "Resolves the libraries in the config's deps.edn (if present) and adds the
  resulting jars to `dcl`. Returns a result map:
  - {:status :none} when there is no deps.edn;
  - {:status :ok :libs [...] :paths [...]} on success;
  Throws on a real resolution failure (the caller degrades gracefully)."
  [^DynamicClassLoader dcl {:keys [deps-file repo-dir offline?]}]
  (if-let [deps-edn (read-deps-edn deps-file)]
    (let [resolve-deps (resolve-deps-fn)
          repos (merge fallback-repos (:mvn/repos deps-edn))
          local-repo (.getAbsolutePath (io/file repo-dir))
          ;; The local-repo redirect keeps the cache project-local. Maven
          ;; resolves from that cache first, so a fully warmed `repo/` (which
          ;; `offline?` documents as the intent) resolves without touching the
          ;; network; the flag is passed through for tools that honor it.
          deps-map (cond-> (assoc deps-edn
                                  :mvn/repos repos
                                  :mvn/local-repo local-repo)
                     offline? (assoc :mvn/offline true))
          _ (.mkdirs (io/file repo-dir))
          lib-map (resolve-deps deps-map nil)
          paths (into [] (comp (mapcat :paths) (distinct)) (vals lib-map))]
      (doseq [^String p paths]
        (.addURL dcl (.toURL (.toURI (io/file p)))))
      {:status :ok
       :libs (vec (sort (map str (keys lib-map))))
       :paths paths
       :offline? (boolean offline?)})
    {:status :none}))
