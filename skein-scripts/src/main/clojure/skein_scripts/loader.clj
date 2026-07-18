(ns skein-scripts.loader
  "Loads plain `.clj` scripts from a directory at runtime and reloads them
  on demand.

  Scripts are ordinary Clojure source, compiled on load (not AOT). They run
  under a dedicated `DynamicClassLoader` parented on the runtime loader
  (Knot under Fabric), so they see the game, other mods and anything a
  `deps.edn` pulled in. Each file is loaded in isolation: one that throws
  logs a full error and does not stop the rest — a single broken script
  never takes the server down.

  Load order is the sorted file name, so a numeric prefix (`10-world.clj`,
  `20-commands.clj`) gives explicit ordering when scripts depend on each
  other.

  Everything here is side-effecting but game-agnostic: it takes a config
  map (see `skein-scripts.config`) and never reaches for FabricLoader, so
  it is testable headlessly."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [skein-scripts.deps :as deps])
  (:import (clojure.lang DynamicClassLoader RT)
           (java.io File)))

;;; The scripts classloader — one per JVM, created lazily. deps.edn jars are
;;; added here; script classes compile into its children.

(defonce ^:private classloader (atom nil))

(defn script-classloader
  "The DynamicClassLoader that owns script classes and deps.edn jars,
  created on first use over the current runtime base loader."
  ^DynamicClassLoader []
  (or @classloader
      (swap! classloader (fn [existing]
                           (or existing (DynamicClassLoader. (RT/baseLoader)))))))

;;; State, exposed for the REPL and the /skein reload command.

(defonce ^:private state
  (atom {:loaded-at nil :scripts [] :deps nil}))

(defn status
  "The last load result: {:loaded-at <inst> :scripts [{:name :status
  :error}] :deps {...}}."
  []
  @state)

;;; Loading

(defn list-scripts
  "The `.clj` files directly under dir, sorted by name (nil-safe: a missing
  or empty directory yields an empty vector)."
  [^File dir]
  (if (and dir (.isDirectory dir))
    (->> (.listFiles dir)
         (filter (fn [^File f] (and (.isFile f) (str/ends-with? (.getName f) ".clj"))))
         (sort-by (fn [^File f] (.getName f)))
         vec)
    []))

(defn- with-script-classloader
  "Runs f with TCCL = the scripts classloader and context-classloader use
  enabled, so `load`/`require` inside compile script classes into it and
  resolve deps.edn jars. Same reasoning as the nREPL server's TCCL switch."
  [f]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (.setContextClassLoader thread (script-classloader))
    (try
      (binding [*use-context-classloader* true]
        (f))
      (finally (.setContextClassLoader thread previous)))))

(defn- load-one
  "Loads a single script File, returning a result map. A script with no
  `(ns ...)` form runs in a fresh `user` namespace so bare `def`s do not
  leak into clojure.core's."
  [^File file]
  (let [result {:name (.getName file) :path (.getAbsolutePath file)}]
    (try
      (binding [*ns* (create-ns 'user)]
        (refer-clojure)
        (with-open [reader (io/reader file)]
          (load-reader reader)))
      (assoc result :status :ok)
      (catch Throwable t
        (assoc result :status :error :error (.getMessage t) :throwable t)))))

(defn load-all!
  "Resolves the deps.edn (if any) into the scripts classloader, then loads
  every script under `:scripts-dir` in order. Returns the status map. Never
  throws for a script error — those land in the per-script `:status`.
  `log` (optional) is called with [level & args] for progress/errors."
  ([config] (load-all! config (fn [& _])))
  ([{:keys [scripts-dir] :as config} log]
   (let [dcl (script-classloader)
         deps-result (try
                       (deps/resolve-into! dcl config)
                       (catch Throwable t
                         (log :error "deps.edn resolution failed — loading scripts without extra deps" t)
                         {:status :error :error (.getMessage t)}))
         files (list-scripts scripts-dir)
         results (with-script-classloader
                   (fn []
                     (mapv (fn [file]
                             (log :info (str "loading " (.getName ^File file)))
                             (let [r (load-one file)]
                               (when (= :error (:status r))
                                 (log :error (str "script " (:name r) " failed") (:throwable r)))
                               r))
                           files)))
         snapshot {:loaded-at (java.time.Instant/now)
                   :scripts (mapv #(dissoc % :throwable) results)
                   :deps deps-result}]
     (reset! state snapshot)
     (let [ok (count (filter #(= :ok (:status %)) results))
           failed (count (filter #(= :error (:status %)) results))]
       (log :info (str "loaded " ok "/" (count results) " scripts"
                       (when (pos? failed) (str ", " failed " failed")))))
     snapshot)))
