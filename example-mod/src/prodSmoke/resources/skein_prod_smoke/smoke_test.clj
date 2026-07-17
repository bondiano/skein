(ns skein-prod-smoke.smoke-test
  "Production smoke test: the built adapter + example-mod jars on the
  real Fabric server launcher (fabric-server-launch, real Knot, JiJ
  extraction — none of the Loom dev classpath). Two boots of a
  dedicated server:

  1. with the opt-in config file — the production nREPL comes up on a
     loopback port, a real nREPL client connects and exercises interop
     with live server classes, game-thread dispatch, re-def and the
     generated mixin (the eval checks live in skein-prod-smoke.client);
  2. without the config file — the port stays closed and the log stays
     free of nREPL mentions: production REPL is strictly opt-in.

  Plain clojure.test, run by `clojure.main -m` from the
  prodReplSmokeTest Gradle task, which passes the jar locations and the
  scratch run dir as system properties. Needs network on first run (the
  launcher downloads the MC server)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests use-fixtures]]
            [skein-prod-smoke.client :as client])
  (:import (java.io File)
           (java.net ConnectException InetSocketAddress ServerSocket Socket)
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
(def ^:private config-file (delay (io/file @run-dir "config" "skein" "nrepl.properties")))

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
    ;; max-players=13 is the marker the REPL checks read back through
    ;; (.getMaxPlayers (.getPlayerList server)) — a full config-file →
    ;; live-server-object → eval round trip.
    (spit (io/file @run-dir "server.properties")
          (str "server-port=" (free-port) "\n"
               "online-mode=false\n"
               "max-players=13\n"
               "level-type=minecraft:flat\n"
               "generate-structures=false\n"
               "sync-chunk-writes=false\n"
               "motd=skein prod REPL smoke\n"))))

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
      (catch ConnectException _ false))))

(defn- wait-for-port [port ^Process server ^File log]
  (let [deadline (.plus (Instant/now) (Duration/ofSeconds 30))]
    (loop []
      (cond
        (can-connect? port) nil
        (and (.isBefore (Instant/now) deadline) (.isAlive server)) (do (Thread/sleep 250) (recur))
        :else (throw (ex-info (str "nREPL port " port " never opened; log:\n" (tail log)) {}))))))

;;; The scenarios

(deftest opt-in-repl-serves-live-server-interop
  (let [nrepl-port (free-port)
        log (io/file @run-dir "console-opt-in.log")]
    (io/make-parents @config-file)
    (spit @config-file (str "enabled=true\nport=" nrepl-port "\n"))
    (let [server (start-server log)]
      (try
        (wait-for log "Done" server)
        (wait-for-port nrepl-port server log)
        (let [r (client/run-checks nrepl-port)]
          (is (= ["3"] (:arith r)) "plain eval")
          (is (= ["\"SKEIN\""] (:interop r)) "java interop")
          (is (= ["13"] (:max-players r)) "interop with the live MinecraftServer")
          (is (= ["\"Server thread\""] (:server-thread r)) "on-game dispatch")
          (is (= [":v2"] (:redef r)) "hotfix re-def in a live prod session")
          (is (str/includes? (apply str (:add-lib-blocked r)) "dev-only")
              "add-lib! must refuse in production")

          (is (= ["[true true]"] (:registry r)) "ruby item + block in the live registries")
          (is (= ["[:block/use :player/join]"] (:event-handlers r))
              "event registrations survived the production boot")
          (is (not= ["[]"] (:mixin-merged r))
              "generated injector merged into the live MinecraftServer class")
          (is (= ["true"] (:mixin-ticks r)) "generated mixin ticks on the live server")
          (is (= ["true"] (:mixin-redef r))
              "re-defed mixin handler picked up by the very next tick")
          (is (= ["[]"] (:players r)) "core-lib interop helpers work in prod"))
        (finally
          (stop-server! server))))
    (is (str/includes? (slurp log) "SKEIN PRODUCTION nREPL ENABLED")
        "activation must shout a warning into the log")))

(deftest without-opt-in-the-port-stays-closed
  (let [probe-port (free-port)
        log (io/file @run-dir "console-no-opt-in.log")]
    (io/delete-file @config-file true)
    (let [server (start-server log)]
      (try
        (wait-for log "Done" server)
        ;; Only the freshly-picked free port is probed: 7888 may be held
        ;; by an unrelated host process (an editor's own nREPL).
        (is (false? (can-connect? probe-port)) "no config, no listener")
        (finally
          (stop-server! server))))
    ;; The mod list still mentions the JiJ'd nrepl jar, so probe for the
    ;; start markers, not the substring.
    (let [console (slurp log)]
      (is (false? (str/includes? console "SKEIN PRODUCTION nREPL ENABLED"))
          "no banner without opt-in")
      (is (false? (str/includes? console "nREPL server listening"))
          "no server start without opt-in"))))

;;; Entry point for `clojure.main -m` (the Gradle task)

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'skein-prod-smoke.smoke-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
