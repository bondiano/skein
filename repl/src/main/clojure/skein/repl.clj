(ns skein.repl
  "nREPL lifecycle + REPL-side helpers.

  Dev: адаптер стартует сервер автоматически (SkeinInit), default
  127.0.0.1:7888. Production: строго opt-in (config/skein/nrepl.properties
  или -Dskein.nrepl.enabled=true), bind только loopback — nREPL-сессия
  означает полный контроль над JVM, наружу порт не открываем никогда.

  Helpers:
  - `on-game` / `on-client` / `on-server` — синхронный диспатч тела на
    игровой тред (клиент и сервер реализуют java.util.concurrent.Executor;
    инстанс берётся из FabricLoader.getGameInstance());
  - `add-lib!` — dev-only подключение библиотеки из Maven Central/Clojars
    в живую сессию через tools.deps."
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
  "Фактический порт слушающего сервера (nil, если не запущен).
  Отличается от запрошенного при :port 0 (эфемерный порт в тестах)."
  []
  (when-let [s @server]
    (.getLocalPort ^ServerSocket (:server-socket s))))

(defn- with-runtime-classloader
  "Вызывает f с TCCL = classloader, загрузивший Clojure (под Fabric — Knot).
  Треды, порождённые внутри (accept-loop сервера → сессии → eval),
  наследуют этот TCCL, поэтому `RT/baseLoader` в eval-сессиях резолвится в
  Knot, а не в app-classloader — иначе классы eval-форм компилируются в
  чужую иерархию и падают с ClassCastException на clojure.lang.IFn."
  [f]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (.setContextClassLoader thread (.getClassLoader clojure.lang.RT))
    (try (f)
         (finally (.setContextClassLoader thread previous)))))

(defn- ensure-user-ns!
  "Гарантирует, что ns `user` (стартовый для nREPL-сессий) реферит
  clojure.core: если первым его создаёт голый create-ns (так делает
  session-middleware nREPL), в нём есть только java.lang-импорты и даже
  (+ 1 2) не резолвится."
  []
  (binding [*ns* (create-ns 'user)]
    (refer-clojure)))

(defn start!
  "Стартует nREPL-сервер (идемпотентно; один сервер на JVM).

  opts: :port (default 7888), :bind (default \"127.0.0.1\"),
  :game-thread-eval? (default false) — opt-in middleware, оборачивающий
  каждый eval в `on-game` (см. skein.repl.middleware)."
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
  "Останавливает сервер, если запущен. Возвращает true, если было что
  останавливать."
  []
  (when-let [s @server]
    (nrepl-server/stop-server s)
    (reset! server nil)
    true))

;;; Game-thread dispatch

(defonce dispatch-executor
  ;; Переопределяемый Executor для диспатча (тесты, нестандартные среды);
  ;; nil → игровой инстанс из loader'а.
  (atom nil))

(defn game-executor
  "Executor игрового треда: MinecraftClient на клиенте, MinecraftServer на
  сервере — оба Executor'ы. Бросает, пока игра не создана (ранние фазы)."
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
  "Запускает f на игровом треде; возвращает promise с {:value v} или
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
  "Как dispatch, но блокируется до результата и пробрасывает исключения.
  Таймаут — защита от вечного ожидания мёртвого игрового треда."
  [f]
  (let [result (deref (dispatch f) dispatch-timeout-ms ::timeout)]
    (cond
      (= result ::timeout)
      (throw (ex-info "Game thread did not run the task in time — is the game alive?"
                      {:timeout-ms dispatch-timeout-ms}))

      (:error result) (throw (:error result))
      :else (:value result))))

(defmacro on-game
  "Выполняет body на игровом треде синхронно, возвращает значение body."
  [& body]
  `(dispatch-sync (fn [] ~@body)))

(defmacro on-client
  "= on-game; имя для читаемости клиентского кода."
  [& body]
  `(dispatch-sync (fn [] ~@body)))

(defmacro on-server
  "= on-game; имя для читаемости серверного кода."
  [& body]
  `(dispatch-sync (fn [] ~@body)))

;;; add-lib (dev-only)

(defn- dev? []
  (.isDevelopmentEnvironment (FabricLoader/getInstance)))

(defn- top-dynamic-classloader
  "Самый верхний DynamicClassLoader в цепочке baseLoader'а — URL'ы,
  добавленные в него, видны всем вложенным загрузчикам REPL-сессии."
  ^DynamicClassLoader []
  (loop [cl (RT/baseLoader) dcl nil]
    (if cl
      (recur (.getParent cl) (if (instance? DynamicClassLoader cl) cl dcl))
      dcl)))

(def ^:private fallback-repos
  {"central" {:url "https://repo1.maven.org/maven2/"}
   "clojars" {:url "https://repo.clojars.org/"}})

(defn add-lib!
  "Dev-only: резолвит lib (символ, напр. 'org.clojure/data.json) указанной
  версии из Maven Central/Clojars через tools.deps и добавляет jar'ы (вместе
  с транзитивными) в classloader текущей REPL-сессии. Возвращает пути.

  Экспериментируй вживую, потом фиксируй зависимость в build.gradle —
  добавленное живёт до перезапуска JVM. В production выключено."
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
