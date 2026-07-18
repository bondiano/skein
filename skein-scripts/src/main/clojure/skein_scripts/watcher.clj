(ns skein-scripts.watcher
  "Optional file watcher: reloads scripts a short debounce after any `.clj`
  under the scripts directory is created, changed or removed. Runs on a
  single daemon thread; opt-in via `watch=true` in scripts.properties.

  The watcher only decides *when* to reload — it calls the supplied
  `reload!` thunk, which is responsible for running the reload on the right
  thread (the game thread, via `skein-scripts.core`)."
  (:import (java.io File)
           (java.nio.file FileSystems Path StandardWatchEventKinds WatchEvent WatchKey WatchService)
           (java.util.concurrent TimeUnit)))

(defonce ^:private watcher (atom nil))

(def ^:private debounce-ms 300)

(defn- clj-event? [events]
  (some (fn [^WatchEvent event]
          (let [ctx (.context event)]
            (and (instance? Path ctx)
                 (.endsWith (str (.getFileName ^Path ctx)) ".clj"))))
        events))

(defn- watch-loop
  "Blocks on the WatchService, coalescing bursts of events into a single
  reload. Exits when the service is closed or the thread is interrupted."
  [^WatchService service reload! log]
  (try
    (loop []
      (when-let [^WatchKey key (try (.take service)
                                    (catch java.nio.file.ClosedWatchServiceException _ nil)
                                    (catch InterruptedException _ nil))]
        (let [relevant? (clj-event? (.pollEvents key))]
          (.reset key)
          (when relevant?
            ;; Drain the tail of a burst (editors write several times) before
            ;; reloading once.
            (loop []
              (when-let [^WatchKey more (.poll service debounce-ms TimeUnit/MILLISECONDS)]
                (.pollEvents more)
                (.reset more)
                (recur)))
            (try (reload!)
                 (catch Throwable t (log :error "watcher reload failed" t))))
          (recur))))
    (catch Throwable t
      (log :error "script watcher stopped" t))))

(defn start!
  "Starts watching `dir` for `.clj` changes (idempotent). `reload!` is a
  0-arg thunk invoked after each debounced change; `log` takes [level &
  args]. No-op when the directory does not exist."
  [^File dir reload! log]
  (when-not @watcher
    (when (.isDirectory dir)
      (let [service (.newWatchService (FileSystems/getDefault))
            path (.toPath dir)]
        (.register path service
                   (into-array java.nio.file.WatchEvent$Kind
                               [StandardWatchEventKinds/ENTRY_CREATE
                                StandardWatchEventKinds/ENTRY_MODIFY
                                StandardWatchEventKinds/ENTRY_DELETE]))
        (let [thread (doto (Thread. #(watch-loop service reload! log) "skein-scripts-watcher")
                       (.setDaemon true))]
          (reset! watcher {:service service :thread thread})
          (.start thread)
          (log :info (str "watching " (.getAbsolutePath dir) " for script changes"))
          true)))))

(defn stop!
  "Stops the watcher if running."
  []
  (when-let [{:keys [^WatchService service]} @watcher]
    (.close service)
    (reset! watcher nil)
    true))
