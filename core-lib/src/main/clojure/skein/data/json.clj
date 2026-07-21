(ns skein.data.json
  "A tiny, dependency-free JSON writer for the build-time datagen pipeline.

  The datagen fork runs with only the game and Clojure on its classpath —
  the same constraint the mixin pipeline works under — so it cannot reach
  for a JSON library. This namespace turns the plain Clojure data the
  generators produce (maps, vectors, strings, keywords, numbers, booleans,
  nil) into pretty-printed JSON text.

  Conventions matching the vanilla resource files:
  - two-space indentation, `\": \"` after keys;
  - object keys are emitted in sorted order, so a regenerated file has a
    stable diff regardless of map iteration order;
  - keyword keys and values render as their `name` (namespaced keywords as
    `ns:name`) — ids and enum-ish values read straight through.

  Pure: no game types, no I/O. `write` returns a string; the caller spits it."
  (:require [clojure.string :as str]))

(defn- escape ^String [^String s]
  (let [sb (StringBuilder. (+ 2 (.length s)))]
    (.append sb \")
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (case c
          \" (.append sb "\\\"")
          \\ (.append sb "\\\\")
          \newline (.append sb "\\n")
          \return (.append sb "\\r")
          \tab (.append sb "\\t")
          (if (< (int c) 0x20)
            (.append sb (format "\\u%04x" (int c)))
            (.append sb c)))))
    (.append sb \")
    (.toString sb)))

(defn- key->str ^String [k]
  (cond
    (keyword? k) (subs (str k) 1)      ; :ns/name -> "ns/name", :x -> "x"
    (string? k) k
    (char? k) (str k)
    :else (throw (ex-info (str "A JSON object key must be a keyword, string or char, got "
                               (pr-str k))
                          {:key k}))))

(defn- scalar->str ^String [v]
  (cond
    (nil? v) "null"
    (string? v) (escape v)
    ;; A namespaced keyword renders as an id ("ns:name") — in this datagen
    ;; context a namespaced keyword is always an identifier, and generators
    ;; that emit ids normalize them to strings first anyway.
    (keyword? v) (escape (if-some [ns (namespace v)] (str ns ":" (name v)) (name v)))
    (boolean? v) (str v)
    (integer? v) (str v)
    (number? v) (let [d (double v)]
                  ;; render whole doubles without a trailing ".0" only when the
                  ;; caller passed an integer; a genuine float keeps its form.
                  (str d))
    (char? v) (escape (str v))
    :else (throw (ex-info (str "Cannot render " (pr-str v) " (" (.getSimpleName (class v))
                               ") as JSON — use a map, vector, string, keyword, number,"
                               " boolean or nil")
                          {:value v}))))

(defn- indent ^String [level]
  (str/join (repeat level "  ")))

(declare write-value)

(defn- write-object [sb m level]
  (if (empty? m)
    (.append ^StringBuilder sb "{}")
    (let [pad (indent (inc level))
          entries (sort-by (comp key->str key) (seq m))]
      (.append ^StringBuilder sb "{\n")
      (dorun
       (map-indexed
        (fn [i [k v]]
          (when (pos? i) (.append ^StringBuilder sb ",\n"))
          (.append ^StringBuilder sb pad)
          (.append ^StringBuilder sb (escape (key->str k)))
          (.append ^StringBuilder sb ": ")
          (write-value sb v (inc level)))
        entries))
      (.append ^StringBuilder sb "\n")
      (.append ^StringBuilder sb (indent level))
      (.append ^StringBuilder sb "}"))))

(defn- write-array [sb xs level]
  (if (empty? xs)
    (.append ^StringBuilder sb "[]")
    (let [pad (indent (inc level))]
      (.append ^StringBuilder sb "[\n")
      (dorun
       (map-indexed
        (fn [i v]
          (when (pos? i) (.append ^StringBuilder sb ",\n"))
          (.append ^StringBuilder sb pad)
          (write-value sb v (inc level)))
        xs))
      (.append ^StringBuilder sb "\n")
      (.append ^StringBuilder sb (indent level))
      (.append ^StringBuilder sb "]"))))

(defn- write-value [sb v level]
  (cond
    (map? v) (write-object sb v level)
    (and (sequential? v) (not (string? v))) (write-array sb v level)
    :else (.append ^StringBuilder sb (scalar->str v))))

(defn write
  "Render a Clojure data value as pretty-printed JSON text (trailing newline)."
  ^String [v]
  (let [sb (StringBuilder.)]
    (write-value sb v 0)
    (.append sb "\n")
    (.toString sb)))
