(ns skein-scripts-smoke.smoke-test
  "Production smoke test: the built adapter + skein-scripts jars on the real
  Fabric server launcher (fabric-server-launch, real Knot, JiJ extraction —
  none of the Loom dev classpath). One boot of a dedicated server with the
  opt-in production nREPL and a populated config/skein directory:

  - a script (10-smoke.clj) loads at the server-started phase and defines a
    var the REPL reads back;
  - a config/skein/deps.edn pulls org.clojure/data.csv — a library that is
    NOT in the tools.deps closure this mod JiJ-bundles — proving real
    dependency resolution inside a production server, and a second script
    (20-deps.clj) requires and uses it;
  - editing 10-smoke.clj on disk and calling (skein-scripts.core/reload!)
    over the REPL hot-reloads it (the reload dispatches onto the server
    thread, the same path as /skein reload).

  Plain clojure.test, run by `clojure.main -m` from the prodReplSmokeTest
  Gradle task, which passes the jar locations and the scratch run dir as
  system properties. Needs network on first run (the launcher downloads the
  MC server; deps.edn resolution downloads data.csv)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests use-fixtures]]
            [skein-scripts-smoke.client :as client])
  (:import (java.io File)
           (java.net InetSocketAddress ServerSocket Socket)
           (java.time Duration Instant)
           (java.util.concurrent TimeUnit)
           (java.util.regex Pattern)))

(def ^:private first-boot-timeout (Duration/ofMinutes 10))
(def ^:private stop-timeout (Duration/ofMinutes 2))

(defn- prop ^String [key]
  (or (System/getProperty key)
      (throw (ex-info (str "Missing system property " key
                           " — run this through the prodReplSmokeTest Gradle task")
                      {:property key}))))

(def ^:private run-dir (delay (io/file (prop "skein.smoke.runDir"))))
(def ^:private launcher-jar (delay (io/file (prop "skein.smoke.launcherJar"))))
(def ^:private nrepl-config (delay (io/file @run-dir "config" "skein" "nrepl.properties")))
(def ^:private scripts-dir (delay (io/file @run-dir "config" "skein" "scripts")))
(def ^:private deps-file (delay (io/file @run-dir "config" "skein" "deps.edn")))
(def ^:private marker-script (delay (io/file @scripts-dir "10-smoke.clj")))

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

;;; Server orchestration

(defn- prepare-run-dir! []
  (let [mods (io/file @run-dir "mods")]
    (when (.isDirectory mods)
      (doseq [^File stale (.listFiles mods)]
        (io/delete-file stale)))
    (.mkdirs mods)
    (doseq [jar (str/split (prop "skein.smoke.modJars")
                           (re-pattern (Pattern/quote File/pathSeparator)))]
      (let [source (io/file jar)]
        (io/copy source (io/file mods (.getName source)))))
    (spit (io/file @run-dir "eula.txt") "eula=true\n")
    (spit (io/file @run-dir "server.properties")
          (str "server-port=" (free-port) "\n"
               "online-mode=false\n"
               "level-type=minecraft:flat\n"
               "generate-structures=false\n"
               "sync-chunk-writes=false\n"
               "motd=skein scripts smoke\n"))
    ;; The Skein config: opt-in prod nREPL, a deps.edn pulling a lib that is
    ;; not in the JiJ'd closure, and two scripts (default server-started
    ;; phase — no scripts.properties needed).
    (io/make-parents @nrepl-config)
    (.mkdirs @scripts-dir)
    (spit @deps-file "{:deps {org.clojure/data.csv {:mvn/version \"1.1.0\"}}}\n")
    (spit @marker-script "(ns smoke.script)\n(def marker :v1)\n")
    (spit (io/file @scripts-dir "20-deps.clj")
          "(ns smoke.deps (:require [clojure.data.csv :as csv]))\n(def row (first (csv/read-csv \"a,b,c\")))\n")))

(use-fixtures :once
  (fn [tests]
    (prepare-run-dir!)
    (tests)))

(defn- tail [^File log]
  (if (.exists log)
    (let [lines (str/split-lines (slurp log))]
      (str/join "\n" (take-last 60 lines)))
    "<no log file>"))

(defn- start-server ^Process [^File log]
  (let [java-bin (str (io/file (System/getProperty "java.home") "bin" "java"))]
    (-> (ProcessBuilder. [java-bin "-Xmx2G" "-jar" (str @launcher-jar) "nogui"])
        (.directory @run-dir)
        (.redirectErrorStream true)
        (.redirectOutput log)
        (.start))))

(defn- stop-server! [^Process server]
  (when (.isAlive server)
    (doto (.getOutputStream server)
      (.write (.getBytes "stop\n"))
      (.flush))
    (when-not (.waitFor server (.toSeconds stop-timeout) TimeUnit/SECONDS)
      (.destroyForcibly server)
      (throw (ex-info (str "server did not stop within " stop-timeout) {})))))

(defn- wait-for
  "Blocks until the console log contains marker (server boot progress)."
  [^File log marker ^Process server]
  (let [deadline (.plus (Instant/now) first-boot-timeout)]
    (loop []
      (cond
        (and (.exists log) (str/includes? (slurp log) marker))
        nil

        (not (.isAlive server))
        (throw (ex-info (str "server exited with code " (.exitValue server)
                             " before '" marker "'; log:\n" (tail log))
                        {}))

        (.isBefore (Instant/now) deadline)
        (do (Thread/sleep 500) (recur))

        :else
        (throw (ex-info (str "no '" marker "' in the server log within " first-boot-timeout
                             "; log:\n" (tail log))
                        {}))))))

(defn- can-connect? [port]
  (with-open [socket (Socket.)]
    (try
      (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) 1000)
      true
      (catch java.net.ConnectException _ false))))

(defn- wait-for-port [port ^Process server ^File log]
  (let [deadline (.plus (Instant/now) (Duration/ofSeconds 30))]
    (loop []
      (cond
        (can-connect? port) nil
        (and (.isBefore (Instant/now) deadline) (.isAlive server)) (do (Thread/sleep 250) (recur))
        :else (throw (ex-info (str "nREPL port " port " never opened; log:\n" (tail log)) {}))))))

;;; The scenario

(deftest scripts-load-deps-resolve-and-reload
  (let [nrepl-port (free-port)
        log (io/file @run-dir "console.log")]
    (spit @nrepl-config (str "enabled=true\nport=" nrepl-port "\n"))
    (let [server (start-server log)]
      (try
        (wait-for log "Done" server)
        (wait-for-port nrepl-port server log)
        (let [rewrite! #(spit @marker-script "(ns smoke.script)\n(def marker :v2)\n")
              r (client/run-checks nrepl-port rewrite!)]
          (is (= [":v1"] (:marker-initial r))
              "the script loaded at the server-started phase")
          (is (= ["[[\"10-smoke.clj\" :ok] [\"20-deps.clj\" :ok]]"] (:status r))
              "both scripts loaded, both ok, in sorted order")
          (is (= [":ok"] (:deps-status r))
              "deps.edn resolved in production")
          (is (= ["[\"a\" \"b\" \"c\"]"] (:deps-parsed r))
              "a script used a library pulled by deps.edn (not in the JiJ closure)")
          (is (= [":v2"] (:marker-reloaded r))
              "editing the script + reload! hot-reloaded it on the live server"))
        (finally
          (stop-server! server))))
    (is (str/includes? (slurp log) "SKEIN PRODUCTION nREPL ENABLED")
        "the opt-in prod REPL announced itself")))

;;; Entry point for `clojure.main -m` (the Gradle task)

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'skein-scripts-smoke.smoke-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
