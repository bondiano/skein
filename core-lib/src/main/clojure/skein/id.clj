(ns skein.id
  "Identifiers as namespaced keywords — the single currency for naming things.

      (id :diamond)        ;=> minecraft:diamond   (a bare keyword defaults to
                           ;                         the minecraft namespace)
      (id :mymod/ruby)     ;=> mymod:ruby
      (id \"mymod:ruby\")    ;=> mymod:ruby
      (id->kw an-identifier) ;=> :minecraft/diamond

  In Clojure code an identifier is always a namespaced keyword; the game's
  `Identifier` object exists only at the coercion boundary. The parsing of a
  keyword or string into [namespace path] is pure and testable on its own via
  `parts`."
  (:require [clojure.string :as str]
            [skein.coerce :as coerce])
  (:import (net.minecraft.resources Identifier)))

(def ^:const default-namespace
  "The namespace a bare (unqualified) name falls back to."
  "minecraft")

(defn parts
  "Split an identifier-like value into a [namespace path] pair of strings.
  Accepts a keyword (`:mymod/ruby`, `:diamond`) or a string (`\"mymod:ruby\"`,
  `\"diamond\"`); a missing namespace becomes `default-namespace`. Pure — no
  game types involved."
  [x]
  (cond
    (keyword? x) [(or (namespace x) default-namespace) (name x)]

    (string? x) (let [i (str/index-of x \:)]
                  (if i
                    [(subs x 0 i) (subs x (inc i))]
                    [default-namespace x]))

    :else (throw (ex-info (str "Cannot read an identifier from " (pr-str x)
                               " — expected a keyword (:mymod/ruby) or a string (\"mymod:ruby\")")
                          {:value x}))))

(defn id
  "Coerce x to a game Identifier. Shorthand for `coerce/->id`."
  ^Identifier [x]
  (coerce/->id x))

(defn id->kw
  "The namespaced keyword for an Identifier: the inverse of `id`."
  [^Identifier identifier]
  (keyword (.getNamespace identifier) (.getPath identifier)))

(extend-protocol coerce/Id
  clojure.lang.Keyword
  (->id [k] (let [[ns path] (parts k)] (Identifier/fromNamespaceAndPath ns path)))

  String
  (->id [s] (let [[ns path] (parts s)] (Identifier/fromNamespaceAndPath ns path)))

  Identifier
  (->id [x] x))
