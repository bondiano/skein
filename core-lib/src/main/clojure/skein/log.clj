(ns skein.log
  "Logging for mod handlers — a bridge over `clojure.tools.logging` to the
  game's log4j/slf4j logger, so a handler's output lands in the server log (and
  the launcher console) tagged with its source and level, not on raw stdout.

  Log from handlers that run in the live game (events, ticks, commands); do not
  print. `println` and `tap>` are REPL conveniences — `println` writes to stdout
  with no level or source, and `tap>` feeds an inspector (see `skein.inspect`) —
  whereas a log call is a real, filterable line the server operator sees:

      (require '[skein.log :as log])
      (log/info \"ruby block placed at\" pos)
      (log/warn \"unexpected state:\" {:pos pos})
      (log/error ex \"handler failed\")

  Each line is tagged with the calling namespace (`mymod.combat` reads as
  clearly as a flat mod id, and separates a mod's subsystems); `logger` names a
  tag explicitly when you want a fixed one. `tools.logging` finds the game's
  log4j2/slf4j backend on the classpath automatically — there is nothing to
  configure, and the backend is the same one the server and other mods log
  through, so levels and formatting stay consistent.

  These are thin pass-throughs of the `clojure.tools.logging` macros: they
  re-emit the underlying macro in the caller, so the logger name resolves to the
  caller's namespace and arguments are only stringified when the level is
  enabled (no cost for a suppressed `debug`)."
  (:require [clojure.tools.logging]
            [clojure.tools.logging.impl :as impl]))

;;; Level macros — one per clojure.tools.logging level. Each expands to the
;;; matching tools.logging macro in the calling namespace, so *ns*-based logger
;;; naming and lazy argument evaluation are preserved.

(defmacro ^:private deflevel [level]
  (let [target (symbol "clojure.tools.logging" (name level))]
    `(defmacro ~level
       ~(str "Logs at " (name level) " level through the game logger for the "
             "calling namespace. Args are a `throwable?` then message parts "
             "(space-joined, like `println`) or a format string via `"
             (name level) "f`. See `skein.log`.")
       {:arglists '~'([message & more] [throwable message & more])}
       [~'& args#]
       (list* '~target args#))))

(deflevel trace)
(deflevel debug)
(deflevel info)
(deflevel warn)
(deflevel error)
(deflevel fatal)

;;; Formatted variants (printf-style) — same levels, format-string first.

(defmacro ^:private deflevelf [level]
  (let [target (symbol "clojure.tools.logging" (str (name level) "f"))
        our (symbol (str (name level) "f"))]
    `(defmacro ~our
       ~(str "Logs at " (name level) " level with a printf-style format string "
             "(optionally a leading throwable). See `skein.log`.")
       {:arglists '~'([fmt & args] [throwable fmt & args])}
       [~'& args#]
       (list* '~target args#))))

(deflevelf trace)
(deflevelf debug)
(deflevelf info)
(deflevelf warn)
(deflevelf error)
(deflevelf fatal)

;;; Named loggers and level checks — for a fixed tag or a guarded expensive log.

(defn logger
  "The underlying logger object for a name (a string tag, a keyword, or a
  namespace). Rarely needed — the level macros pick the calling namespace — but
  useful for a fixed mod-id tag: `(logger :mymod)`."
  [name-or-ns]
  (impl/get-logger clojure.tools.logging/*logger-factory*
                   (cond
                     (keyword? name-or-ns) (subs (str name-or-ns) 1)
                     :else (str name-or-ns))))

(defn enabled?
  "Whether `level` (`:info`, `:debug`, ...) is enabled for `name-or-ns`. Guard
  an expensive-to-build log payload with it."
  [level name-or-ns]
  (impl/enabled? (logger name-or-ns) level))
