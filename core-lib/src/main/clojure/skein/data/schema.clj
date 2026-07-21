(ns skein.data.schema
  "The Malli schema for `skein-data.edn` — the one data file a mod writes to
  drive the build-time datagen (lang, tags, recipes, loot, advancements).

  This validates the *shape* of the declaration (a recipe has a pattern, a
  smelting has an ingredient, a tag maps to a vector of members); whether an
  id actually exists in the game is a separate build check against the real
  registries (see `skein.data.datagen`). Pure data, no game types — unit-
  tested without booting Minecraft.

  Every section is optional; a mod fills only what it needs. Maps that must
  catch a typo are `:closed`, so an unknown key is an error naming it."
  (:require [skein.schemas :as schemas]))

(def ^:private ident
  "An id: a keyword (`:mymod/ruby`) or an `\"ns:path\"` string."
  schemas/id)

(def ^:private ingredient
  "A recipe ingredient: one id, a `\"#tag\"` reference, or a vector of ids
  standing for a choice of any."
  [:or {:error/message "should be an id, a \"#tag\" string, or a vector of ids"}
   ident [:vector ident]])

(def ^:private result
  "A recipe result: an id, or `{:id id :count n}`."
  [:or ident
   [:map {:closed true}
    [:id ident]
    [:count {:optional true} [:int {:min 1}]]]])

(def ^:private label
  "A free-form label key (`:category`, `:group`): a keyword or a string."
  [:or :keyword :string])

;;; Lang

(def lang
  "`{:en_us {key value}}` — locale -> flat translation map. A key is a string
  (`\"block.mymod.ruby\"`) or a keyword (`:block/mymod/ruby`, `/` -> `.`); a
  value is the translated string."
  [:map-of {:error/message "should be a map of locale -> {key value}"}
   [:keyword {:error/message "should be a locale keyword like :en_us"}]
   [:map-of [:or :string :keyword] :string]])

;;; Tags

(def tags
  "`{:block {tag-id [member ...]}}` — registry -> tag -> members. A registry is
  a keyword (`:block`, `:item`, `:enchantment`, ...); a member is an id or a
  `\"#tag\"` reference to another tag."
  [:map-of {:error/message "should be a map of registry -> {tag [members]}"}
   [:keyword {:error/message "should be a registry keyword like :block or :item"}]
   [:map-of ident [:vector ident]]])

;;; Recipes

(def ^:private shaped
  [:map {:closed true}
   [:type [:= :shaped]]
   [:pattern [:vector {:min 1 :max 3 :error/message "should be 1-3 pattern rows of up to 3 chars"} :string]]
   [:key [:map-of [:or char? :string :keyword] ingredient]]
   [:result result]
   [:category {:optional true} label]
   [:group {:optional true} label]])

(def ^:private shapeless
  [:map {:closed true}
   [:type [:= :shapeless]]
   [:ingredients [:vector {:min 1 :max 9 :error/message "should be 1-9 ingredients"} ingredient]]
   [:result result]
   [:category {:optional true} label]
   [:group {:optional true} label]])

(def ^:private cooking
  [:map {:closed true}
   [:type [:enum :smelting :blasting :smoking :campfire]]
   [:ingredient ingredient]
   [:result result]
   [:experience {:optional true} number?]
   [:cookingtime {:optional true} [:int {:min 1}]]
   [:category {:optional true} label]
   [:group {:optional true} label]])

(def ^:private stonecutting
  [:map {:closed true}
   [:type [:= :stonecutting]]
   [:ingredient ingredient]
   [:result result]
   [:group {:optional true} label]])

(def ^:private raw-json
  "The escape hatch: a verbatim JSON body under `:json` for anything the sugar
  does not cover yet."
  [:map {:closed true}
   [:type [:= :raw]]
   [:json [:map-of [:or :keyword :string] :any]]])

(def recipe
  [:multi {:dispatch :type
           :error/message "a recipe needs a :type (:shaped :shapeless :smelting :blasting :smoking :campfire :stonecutting :raw)"}
   [:shaped shaped]
   [:shapeless shapeless]
   [:smelting cooking]
   [:blasting cooking]
   [:smoking cooking]
   [:campfire cooking]
   [:stonecutting stonecutting]
   [:raw raw-json]])

(def recipes
  [:map-of {:error/message "should be a map of recipe-id -> recipe"} ident recipe])

;;; Loot

(def ^:private block-loot
  "A block loot table: `{:type :block}` self-drops the block; `:drop` names a
  different item to drop; `:self false` disables the self-drop."
  [:map {:closed true}
   [:type [:= :block]]
   [:drop {:optional true} ident]
   [:self {:optional true} :boolean]])

(def loot
  [:map-of {:error/message "should be a map of loot-id -> loot table"}
   ident
   [:multi {:dispatch :type
            :error/message "a loot table needs a :type (:block or :raw)"}
    [:block block-loot]
    [:raw raw-json]]])

;;; Advancements — raw passthrough for now (the JSON is broad and evolving).

(def advancements
  [:map-of {:error/message "should be a map of advancement-id -> {:type :raw :json {...}}"}
   ident raw-json])

;;; The whole file

(def data
  [:map {:closed true
         :error/message "skein-data.edn should be a map like {:lang {...} :tags {...} :recipes {...} :loot {...} :advancements {...}}"}
   [:lang {:optional true} lang]
   [:tags {:optional true} tags]
   [:recipes {:optional true} recipes]
   [:loot {:optional true} loot]
   [:advancements {:optional true} advancements]])
