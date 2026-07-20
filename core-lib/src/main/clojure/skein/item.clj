(ns skein.item
  "ItemStack as data — a data-components map, the shape the 26.x item model
  already has:

      (->stack :minecraft/diamond)                       ; a single item
      (->stack {:item :minecraft/diamond_sword :count 1
                :components {:custom-name [:gold \"Blade\"]
                             :enchantments {:minecraft/sharpness 5}
                             :damage 12}})
      (stack-> an-item-stack)                             ; -> the data map

  Components covered here: :custom-name (a skein.text form), :damage, and
  :enchantments (a map of enchantment id -> level). Enchantments are a datapack
  registry, not a built-in one, so coercing them needs a registry access — the
  running server's, or a bound `skein.interop/*registry-access*`; without one
  the coercion raises an actionable error. The map is open — a mod adds more by
  extending `skein.coerce/Stack` and `stack->`. Reading and writing a stack
  touches the live game types only at this boundary."
  (:require [skein.coerce :as coerce]
            [skein.id :as id]
            [skein.interop :as interop]
            [skein.schema :as schema]
            [skein.schemas :as schemas]
            [skein.text :as text])
  (:import (net.minecraft.core.component DataComponents)
           (net.minecraft.core.registries BuiltInRegistries Registries)
           (net.minecraft.world.item Item ItemStack)
           (net.minecraft.world.item.enchantment ItemEnchantments ItemEnchantments$Mutable)))

(defn- item-by-id ^Item [item-id]
  (let [identifier (id/id item-id)
        holder (.get BuiltInRegistries/ITEM identifier)]
    (if (.isPresent holder)
      (.value ^net.minecraft.core.Holder (.get holder))
      (throw (ex-info (str "No item registered for " (pr-str item-id) " (" identifier ")")
                      {:item item-id})))))

(defn- enchantment-holder
  "The Holder<Enchantment> for an enchantment id, from the dynamic registry."
  [ench-id]
  (let [ra (interop/registry-access)]
    (when (nil? ra)
      (throw (ex-info (str ":enchantments need a registry access — enchantments are a datapack "
                           "registry, not in BuiltInRegistries. Build the stack in a server context, "
                           "or bind skein.interop/*registry-access*. Failed resolving " (pr-str ench-id))
                      {:enchantment ench-id})))
    (let [registry (.lookup ra Registries/ENCHANTMENT)]
      (when-not (.isPresent registry)
        (throw (ex-info (str "The enchantment registry is not available in this registry access "
                             "(resolving " (pr-str ench-id) ")")
                        {:enchantment ench-id})))
      (let [holder (.get ^net.minecraft.core.Registry (.get registry) (id/id ench-id))]
        (if (.isPresent holder)
          (.get holder)
          (throw (ex-info (str "No enchantment registered for " (pr-str ench-id)) {:enchantment ench-id})))))))

(defn- apply-enchantments! [^ItemStack stack enchantments]
  (let [mut (ItemEnchantments$Mutable. ItemEnchantments/EMPTY)]
    (doseq [[ench-id level] enchantments]
      (.set mut ^net.minecraft.core.Holder (enchantment-holder ench-id) (int level)))
    (.set stack DataComponents/ENCHANTMENTS (.toImmutable mut))))

(defn- apply-components! [^ItemStack stack components]
  (when-some [nm (:custom-name components)]
    (.set stack DataComponents/CUSTOM_NAME (text/text nm)))
  (when-some [d (:damage components)]
    (.set stack DataComponents/DAMAGE (int d)))
  (when-some [ench (:enchantments components)]
    (apply-enchantments! stack ench))
  stack)

(defn ->stack
  "Coerce to an ItemStack. Shorthand for `coerce/->stack`."
  ^ItemStack [x]
  (coerce/->stack x))

(defn- holder->ench-id [^net.minecraft.core.Holder holder]
  (let [[ns nm] (id/parts (.getRegisteredName holder))]
    (keyword ns nm)))

(defn- read-enchantments [^ItemEnchantments enchantments]
  (into {}
        (map (fn [h] [(holder->ench-id h) (.getLevel enchantments ^net.minecraft.core.Holder h)]))
        (.keySet enchantments)))

(defn stack->
  "The data map for an ItemStack (nil for an empty stack): `{:item :count}`,
  plus `:components` when any are set. The inverse of `->stack`."
  [^ItemStack stack]
  (when-not (.isEmpty stack)
    (let [item-id (id/id->kw (.getKey BuiltInRegistries/ITEM (.getItem stack)))
          custom-name (.get stack DataComponents/CUSTOM_NAME)
          damage (.get stack DataComponents/DAMAGE)
          ^ItemEnchantments enchantments (.get stack DataComponents/ENCHANTMENTS)
          components (cond-> {}
                       custom-name (assoc :custom-name (text/component->data custom-name))
                       damage (assoc :damage damage)
                       (and enchantments (not (.isEmpty enchantments)))
                       (assoc :enchantments (read-enchantments enchantments)))]
      (cond-> {:item item-id :count (.getCount stack)}
        (seq components) (assoc :components components)))))

(extend-protocol coerce/Stack
  clojure.lang.Keyword
  (->stack [k] (ItemStack. (item-by-id k) 1))

  clojure.lang.IPersistentMap
  (->stack [m]
    (schema/guarded
     schemas/item-map m "item map"
     (fn []
       (let [{:keys [item count components]} m]
         (doto (ItemStack. (item-by-id item) (int (or count 1)))
           (apply-components! components))))))

  ItemStack
  (->stack [s] s))
