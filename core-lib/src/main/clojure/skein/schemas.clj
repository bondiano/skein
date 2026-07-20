(ns skein.schemas
  "The Malli schemas for every data format the FP layer accepts — one source of
  truth for what a valid item map, blockstate, or `register!` declaration looks
  like. Pure data: no game types are imported, so the schemas validate (and are
  unit-tested) without booting the game. Each domain namespace requires this one
  and runs its boundary through `skein.schema` (`validate!` for declarations,
  `guarded` for coercion).

  A schema carries a custom `:error/message` where the default Malli wording
  (\"invalid type\") would not say what to fix. Coercion maps that a mod extends
  (an item's `:components`) are open; declaration maps that must catch typos
  (a block/item declaration) are closed, so an unknown key is an error naming
  the misspelling."
  (:require [malli.core :as m]))

;;; Shared leaf schemas

(def id
  "An identifier: a keyword (`:mymod/ruby`, bare `:diamond` -> minecraft) or an
  `\"ns:path\"` string. A single `:fn` so a mismatch reads as one clear line
  rather than one per accepted shape."
  [:fn {:error/message "should be an id keyword (:mymod/ruby) or an \"ns:path\" string"}
   (fn [v] (or (keyword? v) (string? v)))])

(def item-count
  [:int {:min 1 :error/message "should be a positive item count (>= 1)"}])

(def pos
  "A position: an `[x y z]` vector of numbers."
  [:tuple {:error/message "should be an [x y z] position vector"} number? number? number?])

(def text-form
  "A `skein.text` form: a string, a `[tag & children]` vector, a node map, or a
  ready Component. Structural only here — `skein.text` owns the full grammar."
  [:fn {:error/message "should be text: a string, a [tag & children] vector, or a node map"}
   (fn [v] (or (string? v) (keyword? v) (vector? v) (map? v)
               (and (some? v) (not (number? v)) (not (boolean? v)))))])

;;; Item

(def component-map
  "The `:components` of an item map. Open — a mod adds its own components — with
  the built-in ones (custom-name, damage, enchantments) shape-checked when
  present."
  [:map {:closed false}
   [:custom-name {:optional true} text-form]
   [:damage {:optional true} [:int {:min 0 :error/message "should be a non-negative damage value"}]]
   [:enchantments {:optional true}
    [:map-of {:error/message "should be a map of enchantment id -> level"}
     id [:int {:min 1 :error/message "should be an enchantment level >= 1"}]]]])

(def item-map
  "An item as data: `{:item id :count n :components {...}}`. A bare id keyword is
  the other accepted form and needs no schema (any keyword is a candidate id);
  the boundary validates this map form only when the input is a map."
  [:map {:closed true}
   [:item id]
   [:count {:optional true} item-count]
   [:components {:optional true} component-map]])

;;; Blockstate

(def prop-value
  "A block property value: a keyword (enum property), a boolean, or an integer."
  [:fn {:error/message "should be a keyword, boolean, or integer property value"}
   (fn [v] (or (keyword? v) (boolean? v) (int? v)))])

(def blockstate-map
  "A blockstate as data: `{:block id :props {name value}}`. A bare id keyword is
  the other accepted form; the boundary validates this map form only for maps."
  [:map {:closed true}
   [:block id]
   [:props {:optional true} [:map-of :keyword prop-value]]])

;;; register! declarations

(def strength
  "A block :strength: one number (hardness = resistance) or `[hardness resistance]`."
  [:fn {:error/message "should be a number or an [hardness resistance] pair"}
   (fn [v] (or (number? v)
               (and (vector? v) (= 2 (count v)) (every? number? v))))])

(def block-decl
  "One block declaration in `register!` (`:blocks {id decl}`)."
  [:map {:closed true}
   [:strength {:optional true} strength]
   [:hardness {:optional true} number?]
   [:resistance {:optional true} number?]
   [:light-level {:optional true} [:int {:min 0 :max 15 :error/message "should be a light level 0-15"}]]
   [:friction {:optional true} number?]
   [:no-collision {:optional true} :boolean]
   [:requires-tool {:optional true} :boolean]
   [:instabreak {:optional true} :boolean]
   [:item? {:optional true} :boolean]
   [:group {:optional true} id]])

(def rarity
  [:enum {:error/message "should be one of :common :uncommon :rare :epic"}
   :common :uncommon :rare :epic])

(def item-decl
  "One item declaration in `register!` (`:items {id decl}`)."
  [:map {:closed true}
   [:stacks-to {:optional true} [:int {:min 1 :max 99}]]
   [:durability {:optional true} [:int {:min 1}]]
   [:fire-resistant {:optional true} :boolean]
   [:rarity {:optional true} rarity]
   [:group {:optional true} id]])

(def register-decl
  "The whole `register!` map: `{:blocks {id block-decl} :items {id item-decl}}`.
  Ids must be namespace-qualified keywords (a bare keyword has no mod to own it)."
  (let [content-id [:and {:error/message "should be a namespace-qualified id like :mymod/ruby"}
                    :keyword [:fn {:error/message "needs a namespace (:mymod/ruby, not :ruby)"} namespace]]]
    [:map {:closed true
           :error/message "should be a map like {:blocks {...} :items {...}}"}
     [:blocks {:optional true} [:map-of content-id block-decl]]
     [:items {:optional true} [:map-of content-id item-decl]]]))

;;; Event payloads — the shape of the `:data` map a pure handler (events/on-pure!)
;;; receives per event. The values are live game objects, documented by role
;;; rather than validated (the layer produces these maps, so there is nothing to
;;; gate); these schemas exist for REPL introspection and help. `:any` marks a
;;; live object whose richer coercion is the caller's to do.

(def event-payloads
  "event-key -> a `[:map ...]` schema of the keys a pure handler receives."
  {:server/starting   [:map [:server :any]]
   :server/started    [:map [:server :any]]
   :server/stopping   [:map [:server :any]]
   :server/stopped    [:map [:server :any]]
   :server/tick-start [:map [:server :any]]
   :server/tick-end   [:map [:server :any]]
   :player/join       [:map [:player :any]]
   :player/disconnect [:map [:player :any]]
   :block/use    [:map [:player :any] [:world :any] [:hand :any] [:hit :any]]
   :block/attack [:map [:player :any] [:world :any] [:hand :any] [:pos :any] [:direction :any]]
   :item/use     [:map [:player :any] [:world :any] [:hand :any]]})

(defn payload-keys
  "The keys of an event's payload map (a sorted vector), or nil if unknown."
  [event-key]
  (when-some [schema (event-payloads event-key)]
    (mapv first (rest schema))))

;;; Precompiled validators — building a validator is not free, so do it once at
;;; load rather than on every boundary call.

(def ^:private compiled (atom {}))

(defn validator
  "A cached `m/validator` for a schema value (compiled once)."
  [schema]
  (or (@compiled schema)
      (let [v (m/validator schema)]
        (swap! compiled assoc schema v)
        v)))
