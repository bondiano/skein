(ns skein-scripts.core
  "Skein Scripts entrypoint: reads the config, registers the `/skein`
  command and loads the scripts at the configured phase.

  Scripts are arbitrary Clojure evaluated on the server — see the mod's
  README for the trust and performance model. Loading and reloading run on
  the server thread, because scripts touch game state; a script that throws
  is isolated and logged, never fatal.

  skein.repl is reached through requiring-resolve (as core-lib does): a
  compile-time require would drag nREPL into this mod's AOT."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.events :as events]
            [skein-scripts.command :as command]
            [skein-scripts.config :as config]
            [skein-scripts.loader :as loader]
            [skein-scripts.watcher :as watcher])
  (:import (java.io File)
           (net.fabricmc.loader.api FabricLoader)
           (org.slf4j Logger LoggerFactory)))

(def ^:private ^Logger logger (LoggerFactory/getLogger "skein-scripts"))

(defn- log
  "Logs at level (:info/:warn/:error). A trailing Throwable is passed to the
  logger as the cause."
  [level & args]
  (let [throwable (when (instance? Throwable (last args)) (last args))
        parts (if throwable (butlast args) args)
        msg (str "[skein-scripts] " (str/join " " (map str parts)))]
    (case level
      :error (if throwable (.error logger msg ^Throwable throwable) (.error logger msg))
      :warn (.warn logger msg)
      (.info logger msg))))

(defonce ^:private current-config (atom nil))

(defn- game-available? []
  (some? (.getGameInstance (FabricLoader/getInstance))))

(defn- reload-here!
  "Loads all scripts on the current thread. Used from the command and the
  lifecycle hook, both of which already run on the server thread."
  []
  (loader/load-all! @current-config log))

(defn reload!
  "Reloads all scripts, dispatched onto the server thread when a game is
  running (safe from the REPL and the file watcher; scripts mutate game
  state). Returns the load status map."
  []
  (if (game-available?)
    ((requiring-resolve 'skein.repl/dispatch-sync) reload-here!)
    (reload-here!)))

(defn- maybe-watch! [config]
  (when (:watch? config)
    (watcher/start! (:scripts-dir config) reload! log)))

(defn on-load-phase
  "Lifecycle handler (a var, so it hot-reloads): first script load at the
  configured server phase, then starts the watcher if enabled. Runs on the
  server thread."
  [_server]
  (reload-here!)
  (maybe-watch! @current-config))

(defn- read-config []
  (let [loader (FabricLoader/getInstance)
        base (.toFile (.resolve (.getConfigDir loader) "skein"))
        props-file (io/file base "scripts.properties")
        props (try
                (config/load-file-props props-file)
                (catch Throwable t
                  (log :error "cannot read scripts.properties — using defaults" t)
                  nil))]
    (config/parse base props)))

(defn init
  "The Fabric `main` entrypoint (see fabric.mod.json)."
  []
  (let [config (read-config)]
    (reset! current-config config)
    (log :info (str "phase=" (name (:phase config))
                    " watch=" (:watch? config)
                    " offline=" (:offline? config)
                    " scripts-dir=" (.getAbsolutePath ^File (:scripts-dir config))))
    (command/register! reload-here! loader/status)
    (case (:phase config)
      :mod-init (do (reload-here!) (maybe-watch! config))
      :server-starting (events/on! :server/starting #'on-load-phase)
      :server-started (events/on! :server/started #'on-load-phase))))
