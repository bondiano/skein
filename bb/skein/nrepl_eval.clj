(ns skein.nrepl-eval
  "A tiny nREPL client for driving a running Skein REPL straight from the shell.

  This is the eval half of the `bb repl:*` launchers: those start (or connect a
  human editor to) an nREPL; this one lets a headless caller — a coding agent, a
  CI step, a one-off check — send a form and read back the value, stdout, stderr
  and any exception. It speaks bencode over a raw socket (both ship inside
  Babashka), so there is no extra dependency to install and nothing MCP-shaped
  to run.

  Ports it knows about (see bb.edn / the dev adapter):
    7888  a running game instance (dev adapter opens this)
    7881  the `repl` module   (bb repl:repl)
    7882  the `core-lib` module (bb repl:core-lib)
    7883  the `skein-scripts` module (bb repl:scripts)

  Namespace and defs survive between calls: the cloned nREPL session id is
  cached in `.nrepl-session-<port>` (gitignored) and reused, so a sequence of
  evals against one port behaves like one continuous REPL. Pass `--fresh` to
  start a new session; a session the server no longer recognises (it was
  restarted) is transparently replaced."
  (:require [babashka.fs :as fs]
            [bencode.core :as bencode]
            [clojure.string :as str])
  (:import [java.net Socket InetSocketAddress]
           [java.io PushbackInputStream]))

;; ---------------------------------------------------------------------------
;; Port discovery

(def ^:private known-ports
  "Live-probe order and the human label printed for each."
  (array-map
   7888 "running game (dev adapter)"
   7881 "repl module (standalone)"
   7882 "core-lib module (standalone)"
   7883 "skein-scripts module (standalone)"))

(defn- live?
  "True if something accepts a TCP connection at host:port within `timeout` ms."
  [host port timeout]
  (try
    (with-open [s (Socket.)]
      (.connect s (InetSocketAddress. ^String host (int port)) (int timeout))
      true)
    (catch Exception _ false)))

(defn- port-from-file
  "The port Loom/nREPL wrote to `.nrepl-port` in the repo root, if any."
  []
  (when (fs/exists? ".nrepl-port")
    (some-> (slurp ".nrepl-port") str/trim not-empty parse-long)))

(defn discover
  "Live nREPL ports, `.nrepl-port` first, then the known standalone ports."
  [host]
  (let [candidates (distinct (remove nil? (cons (port-from-file) (keys known-ports))))]
    (filterv #(live? host % 300) candidates)))

;; ---------------------------------------------------------------------------
;; bencode helpers — dict keys arrive as strings, string values as byte arrays

(defn- ->str [x]
  (if (bytes? x) (String. ^bytes x "UTF-8") x))

(defn- decode
  "Recursively turn a bencode-read value into plain Clojure data: keyword keys,
  string leaves, vectors for lists."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(keyword (->str k)) (decode v)])) x)
    (sequential? x) (mapv decode x)
    :else (->str x)))

(defn- send! [out msg]
  (bencode/write-bencode out msg)
  (.flush out))

(defn- read-msg [in]
  (decode (bencode/read-bencode in)))

;; ---------------------------------------------------------------------------
;; Session cache — one nREPL session per port, so state persists across calls

(defn- session-file [port] (str ".nrepl-session-" port))

(defn- clone-session [in out]
  (send! out {"op" "clone"})
  (loop []
    (let [m (read-msg in)]
      (or (:new-session m) (recur)))))

(defn- cached-session [port]
  (let [f (session-file port)]
    (when (fs/exists? f) (-> f slurp str/trim not-empty))))

(defn- cache-session! [port sid]
  (spit (session-file port) sid))

;; ---------------------------------------------------------------------------
;; Eval

(defn- run-eval
  "One eval round-trip on an open connection. Returns a result map with
  :values :out :err :ns :ex plus :status (vector of strings)."
  [in out {:keys [code session ns]}]
  (send! out (cond-> {"op" "eval" "code" code "session" session}
               ns (assoc "ns" ns)))
  (loop [acc {:session session :values [] :out "" :err "" :status []}]
    (let [m (read-msg in)
          acc (cond-> acc
                (:out m)     (update :out str (:out m))
                (:err m)     (update :err str (:err m))
                (:value m)   (update :values conj (:value m))
                (:ns m)      (assoc :ns (:ns m))
                (:ex m)      (assoc :ex (:ex m))
                (:status m)  (update :status into (:status m)))]
      (if (some #{"done"} (:status acc))
        acc
        (recur acc)))))

(defn eval-code
  "Connect to host:port, evaluate `code`, return the result map. Reuses (or
  creates) a cached session unless :fresh is set; silently re-clones if the
  server has forgotten the cached session."
  [{:keys [host port code ns timeout fresh]
    :or {host "127.0.0.1" timeout 30000}}]
  (with-open [sock (Socket. ^String host (int port))]
    (.setSoTimeout sock (int timeout))
    (let [out (.getOutputStream sock)
          in  (PushbackInputStream. (.getInputStream sock))
          sid (or (and (not fresh) (cached-session port))
                  (clone-session in out))
          result (run-eval in out {:code code :session sid :ns ns})]
      (if (some #{"unknown-session"} (:status result))
        ;; server was restarted — start over with a fresh session
        (let [sid' (clone-session in out)
              result' (run-eval in out {:code code :session sid' :ns ns})]
          (cache-session! port sid')
          result')
        (do (cache-session! port sid) result)))))

;; ---------------------------------------------------------------------------
;; CLI

(defn- print-result [port result]
  (println (str ";; port " port
                (when-let [label (known-ports port)] (str " — " label))
                (when-let [n (:ns result)] (str " [" n "]"))))
  (when (seq (:out result))
    (print (:out result)) (flush))
  (when (seq (:err result))
    (binding [*out* *err*] (print (:err result)) (flush)))
  (doseq [v (:values result)]
    (println (str "=> " v)))
  (when-let [ex (:ex result)]
    (binding [*out* *err*] (println (str "!! " ex))))
  (:ex result))

(defn- parse-args
  "Splits argv into an options map plus the trailing code string(s)."
  [args]
  (loop [args args, opts {}]
    (let [[a & more] args]
      (cond
        (nil? a) (assoc opts :code (str/join " " (:free opts)))
        (#{"-p" "--port"} a) (recur (rest more) (assoc opts :port (parse-long (first more))))
        (#{"-n" "--ns"} a)   (recur (rest more) (assoc opts :ns (first more)))
        (#{"-t" "--timeout"} a) (recur (rest more) (assoc opts :timeout (parse-long (first more))))
        (= "--fresh" a)      (recur more (assoc opts :fresh true))
        (#{"-f" "--file"} a) (recur (rest more) (assoc opts :file (first more)))
        (#{"--discover-ports" "--ports"} a) (recur more (assoc opts :discover true))
        :else (recur more (update opts :free (fnil conj []) a))))))

(defn -main [& args]
  (let [{discover? :discover :keys [port ns timeout fresh file code]} (parse-args args)
        host "127.0.0.1"]
    (cond
      discover?
      (let [ports (discover host)]
        (if (seq ports)
          (doseq [p ports]
            (println (str p "  " (known-ports p "unknown/editor nREPL"))))
          (do (println "No live nREPL found. Start one with `bb repl:*`, or run the game (dev adapter opens :7888).")
              (System/exit 1))))

      :else
      (let [port (or port
                     (first (discover host))
                     (do (binding [*out* *err*]
                           (println "No -p PORT and no live nREPL to auto-pick. Try `bb nrepl:ports`."))
                         (System/exit 1)))
            code (cond
                   file (str "(load-file " (pr-str (str (fs/absolutize file))) ")")
                   (not (str/blank? code)) code
                   :else (str/trim (slurp *in*)))
            result (eval-code (cond-> {:host host :port port :code code :fresh fresh}
                                ns (assoc :ns ns)
                                timeout (assoc :timeout timeout)))
            failed? (print-result port result)]
        (when failed? (System/exit 1))))))
