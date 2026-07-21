(ns skein.data.ids
  "Identifier handling for the datagen surface — pure, game-free, so the
  generators are unit-testable without booting Minecraft.

  Ids in `skein-data.edn` are keywords (`:mymod/ruby`, bare `:diamond` ->
  minecraft) or `\"ns:path\"` strings; in the emitted JSON they must be the
  colon form (`\"minecraft:diamond\"`) — a keyword poured straight into JSON
  would keep its slash (`\"minecraft/diamond\"`) and silently mismatch the
  game. `id-str` is the one conversion the generators run every reference
  through.

  A tag reference (an ingredient or a tag member that points at another tag)
  is written `#ns:path` in vanilla JSON; the EDN spells it the same way as a
  string, or as a keyword tagged with a leading `#` in its path — `normalize`
  keeps the `#` and normalizes the id behind it.

  `closest` powers the build-time \"unknown id — did you mean\" suggestion,
  the headline fail-fast for a content-mod typo."
  (:require [clojure.string :as str]))

(def ^:const default-namespace "minecraft")

(defn parts
  "Split an id-like value into a `[namespace path]` pair of strings; a missing
  namespace becomes `default-namespace`. Accepts a keyword or a string."
  [x]
  (cond
    (keyword? x) [(or (namespace x) default-namespace) (name x)]
    (string? x) (let [i (str/index-of x \:)]
                  (if i
                    [(subs x 0 i) (subs x (inc i))]
                    [default-namespace x]))
    :else (throw (ex-info (str "Cannot read an id from " (pr-str x)
                               " — expected a keyword (:mymod/ruby) or a string (\"mymod:ruby\")")
                          {:value x}))))

(defn id-str
  "The canonical `\"ns:path\"` string for an id keyword/string."
  ^String [x]
  (let [[ns path] (parts x)] (str ns ":" path)))

(defn tag-ref?
  "Whether `x` is a tag reference (`\"#ns:path\"` or a keyword whose path
  starts with `#`)."
  [x]
  (or (and (string? x) (str/starts-with? x "#"))
      (and (keyword? x) (str/starts-with? (name x) "#"))))

(defn normalize
  "Normalize a recipe/tag reference: a plain id -> `\"ns:path\"`, a tag
  reference -> `\"#ns:path\"` (the `#` preserved, the id behind it normalized)."
  ^String [x]
  (if (tag-ref? x)
    (let [raw (if (keyword? x)
                (str (some-> (namespace x) (str ":")) (subs (name x) 1))
                (subs x 1))]
      (str "#" (id-str raw)))
    (id-str x)))

;;; "Did you mean" — Levenshtein distance over id strings.

(defn levenshtein
  "Edit distance between two strings (one row of the DP matrix at a time)."
  [^String a ^String b]
  (let [m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 0 prev (vec (range (inc n)))]
        (if (= i m)
          (peek prev)
          (let [ai (.charAt a i)
                row (reduce
                     (fn [row j]
                       (let [cost (if (= ai (.charAt b j)) 0 1)]
                         (conj row (min (inc (peek row))
                                        (inc (nth prev (inc j)))
                                        (+ cost (nth prev j))))))
                     [(inc i)]
                     (range n))]
            (recur (inc i) row)))))))

(defn closest
  "Up to `limit` of `candidates` (id strings) nearest `target` (an id string)
  within `max-distance` edits, nearest first. For the typo suggestion list."
  [target candidates & {:keys [max-distance limit] :or {max-distance 3 limit 5}}]
  (->> candidates
       (map (fn [c] [c (levenshtein target c)]))
       (filter (fn [[_ d]] (<= d max-distance)))
       (sort-by second)
       (take limit)
       (mapv first)))

(defn did-you-mean
  "The tail of an \"unknown id\" error: `\" — did you mean ...?\"` when any
  candidate is close, otherwise an empty string."
  [target candidates]
  (let [suggestions (closest target candidates)]
    (if (seq suggestions)
      (str " — did you mean " (str/join ", " suggestions) "?")
      "")))
