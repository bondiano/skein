(ns skein.repl
  "nREPL lifecycle + REPL-side helpers.

  Dev: the adapter starts the server automatically (SkeinInit), default
  127.0.0.1:7888. Production: strictly opt-in (config/skein/nrepl.properties
  or -Dskein.nrepl.enabled=true), loopback bind only — an nREPL session
  means full control over the JVM, the port is never opened to the outside.

  Helpers:
  - `on-game` / `on-client` / `on-server` — synchronous dispatch of the
    body onto the game thread (both the client and the server implement
    java.util.concurrent.Executor; the instance comes from
    FabricLoader.getGameInstance());
  - `add-lib!` — dev-only loading of a library from Maven Central/Clojars
    into the live session via tools.deps."
  (:require [clojure.java.io :as io]
            [nrepl.server :as nrepl-server])
  (:import (clojure.lang DynamicClassLoader RT)
           (java.net ServerSocket)
           (java.util.concurrent Executor)
           (net.fabricmc.loader.api FabricLoader)))

;;; nREPL lifecycle

(defonce ^:private server (atom nil))

(defn started? [] (some? @server))

(defn port
  "The actual port of the listening server (nil when not running).
  Differs from the requested one with :port 0 (an ephemeral port in
  tests)."
  []
  (when-let [s @server]
    (.getLocalPort ^ServerSocket (:server-socket s))))

(defn- with-runtime-classloader
  "Calls f with TCCL = the classloader that loaded Clojure (under Fabric
  that is Knot). Threads spawned inside (the server's accept loop →
  sessions → eval) inherit this TCCL, so `RT/baseLoader` in eval sessions
  resolves to Knot rather than the app classloader — otherwise the
  classes of eval'd forms compile into a foreign hierarchy and fail with
  a ClassCastException on clojure.lang.IFn."
  [f]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (.setContextClassLoader thread (.getClassLoader clojure.lang.RT))
    (try (f)
         (finally (.setContextClassLoader thread previous)))))

(defn- ensure-user-ns!
  "Makes sure the ns `user` (the starting ns of nREPL sessions) refers
  clojure.core: when a bare create-ns makes it first (as nREPL's
  session middleware does), it only has the java.lang imports and even
  (+ 1 2) does not resolve."
  []
  (binding [*ns* (create-ns 'user)]
    (refer-clojure)))

(defn start!
  "Starts the nREPL server (idempotent; one server per JVM).

  opts: :port (default 7888), :bind (default \"127.0.0.1\"),
  :game-thread-eval? (default false) — opt-in middleware wrapping every
  eval in `on-game` (see skein.repl.middleware)."
  ([] (start! {}))
  ([{:keys [port bind game-thread-eval?]
     :or {port 7888 bind "127.0.0.1" game-thread-eval? false}}]
   (or @server
       (with-runtime-classloader
        (fn []
          (ensure-user-ns!)
          (let [handler (if game-thread-eval?
                          (nrepl-server/default-handler
                           (requiring-resolve 'skein.repl.middleware/wrap-game-thread))
                          (nrepl-server/default-handler))
                srv (nrepl-server/start-server :port port :bind bind :handler handler)]
            (reset! server srv)
            srv))))))

(defn stop!
  "Stops the server if it is running. Returns true when there was one to
  stop."
  []
  (when-let [s @server]
    (nrepl-server/stop-server s)
    (reset! server nil)
    true))

;;; Game-thread dispatch

(defonce dispatch-executor
  ;; An overridable Executor for dispatch (tests, non-standard setups);
  ;; nil → the game instance from the loader.
  (atom nil))

(defn game-executor
  "The game-thread Executor: MinecraftClient on the client, MinecraftServer
  on the server — both are Executors. Throws until the game exists (early
  phases)."
  ^Executor []
  (or @dispatch-executor
      (let [game (.getGameInstance (FabricLoader/getInstance))]
        (cond
          (nil? game)
          (throw (IllegalStateException.
                  "No game instance yet — the game thread exists only after startup; use plain eval for early phases"))

          (instance? Executor game) game

          :else
          (throw (IllegalStateException.
                  (str "Game instance " (class game) " is not an Executor — cannot dispatch")))))))

(defn dispatch
  "Runs f on the game thread; returns a promise of {:value v} or
  {:error t}."
  [f]
  (let [p (promise)]
    (.execute (game-executor)
              (fn []
                (deliver p (try {:value (f)}
                                (catch Throwable t {:error t})))))
    p))

(def ^:private dispatch-timeout-ms 60000)

(defn dispatch-sync
  "Like dispatch, but blocks until the result and rethrows exceptions.
  The timeout guards against waiting forever on a dead game thread."
  [f]
  (let [result (deref (dispatch f) dispatch-timeout-ms ::timeout)]
    (cond
      (= result ::timeout)
      (throw (ex-info "Game thread did not run the task in time — is the game alive?"
                      {:timeout-ms dispatch-timeout-ms}))

      (:error result) (throw (:error result))
      :else (:value result))))

(defmacro on-game
  "Runs body on the game thread synchronously, returns body's value."
  [& body]
  `(dispatch-sync (fn [] ~@body)))

(defmacro on-client
  "= on-game; the name reads better in client-side code."
  [& body]
  `(dispatch-sync (fn [] ~@body)))

(defmacro on-server
  "= on-game; the name reads better in server-side code."
  [& body]
  `(dispatch-sync (fn [] ~@body)))

;;; add-lib (dev-only)

(defn- dev? []
  (.isDevelopmentEnvironment (FabricLoader/getInstance)))

(defn- top-dynamic-classloader
  "The topmost DynamicClassLoader in the baseLoader chain — URLs added to
  it are visible to all the nested loaders of the REPL session."
  ^DynamicClassLoader []
  (loop [cl (RT/baseLoader) dcl nil]
    (if cl
      (recur (.getParent cl) (if (instance? DynamicClassLoader cl) cl dcl))
      dcl)))

(def ^:private fallback-repos
  {"central" {:url "https://repo1.maven.org/maven2/"}
   "clojars" {:url "https://repo.clojars.org/"}})

(defn add-lib!
  "Dev-only: resolves lib (a symbol, e.g. 'org.clojure/data.json) of the
  given version from Maven Central/Clojars via tools.deps and adds the
  jars (with transitives) to the classloader of the current REPL session.
  Returns the paths.

  Experiment live, then pin the dependency in build.gradle — anything
  added lives until the JVM restarts. Disabled in production."
  [lib version]
  (when-not (dev?)
    (throw (IllegalStateException.
            "add-lib! is dev-only: in production mods must be self-contained (bundle deps at build time)")))
  (let [resolve-deps (try (requiring-resolve 'clojure.tools.deps/resolve-deps)
                          (catch java.io.FileNotFoundException _ nil))]
    (when-not resolve-deps
      (throw (IllegalStateException.
              (str "add-lib! needs org.clojure/tools.deps on the classpath — "
                   "the Skein Gradle plugin adds it to dev runs (localRuntime)"))))
    (let [repos (or (try @(requiring-resolve 'clojure.tools.deps.util.maven/standard-repos)
                         (catch Throwable _ nil))
                    fallback-repos)
          lib-map (resolve-deps {:deps {lib {:mvn/version version}}
                                 :mvn/repos repos}
                                nil)
          paths (into [] (comp (mapcat :paths) (distinct)) (vals lib-map))
          dcl (or (top-dynamic-classloader)
                  (throw (IllegalStateException.
                          "No DynamicClassLoader in scope — call add-lib! from a REPL session")))]
      (doseq [^String p paths]
        (.addURL dcl (.toURL (.toURI (io/file p)))))
      paths)))
