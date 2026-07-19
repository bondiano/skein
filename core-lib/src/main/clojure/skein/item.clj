(ns skein.item
  "ItemStack as data — a data-components map, the shape the 26.x item model
  already has:

      (->stack :minecraft/diamond)                       ; a single item
      (->stack {:item :minecraft/diamond_sword :count 1
                :components {:custom-name [:gold \"Blade\"]
                             :damage 12}})
      (stack-> an-item-stack)                             ; -> the data map

  Components covered here are the common, registry-free ones: :custom-name (a
  skein.text form) and :damage. The map is open — a mod adds more by extending
  `skein.coerce/Stack` and `stack->`. Reading and writing a stack touches the
  live game types only at this boundary."
  (:require [skein.coerce :as coerce]
            [skein.id :as id]
            [skein.text :as text])
  (:import (net.minecraft.core.component DataComponents)
           (net.minecraft.core.registries BuiltInRegistries)
           (net.minecraft.world.item Item ItemStack)))

(defn- item-by-id ^Item [item-id]
  (let [identifier (id/id item-id)
        holder (.get BuiltInRegistries/ITEM identifier)]
    (if (.isPresent holder)
      (.value (.get holder))
      (throw (ex-info (str "No item registered for " (pr-str item-id) " (" identifier ")")
                      {:item item-id})))))

(defn- apply-components! [^ItemStack stack components]
  (when-some [nm (:custom-name components)]
    (.set stack DataComponents/CUSTOM_NAME (text/text nm)))
  (when-some [d (:damage components)]
    (.set stack DataComponents/DAMAGE (int d)))
  stack)

(defn ->stack
  "Coerce to an ItemStack. Shorthand for `coerce/->stack`."
  ^ItemStack [x]
  (coerce/->stack x))

(defn stack->
  "The data map for an ItemStack (nil for an empty stack): `{:item :count}`,
  plus `:components` when any are set. The inverse of `->stack`."
  [^ItemStack stack]
  (when-not (.isEmpty stack)
    (let [item-id (id/id->kw (.getKey BuiltInRegistries/ITEM (.getItem stack)))
          custom-name (.get stack DataComponents/CUSTOM_NAME)
          damage (.get stack DataComponents/DAMAGE)
          components (cond-> {}
                       custom-name (assoc :custom-name (text/component->data custom-name))
                       damage (assoc :damage damage))]
      (cond-> {:item item-id :count (.getCount stack)}
        (seq components) (assoc :components components)))))

(extend-protocol coerce/Stack
  clojure.lang.Keyword
  (->stack [k] (ItemStack. (item-by-id k) 1))

  clojure.lang.IPersistentMap
  (->stack [{:keys [item count components]}]
    (doto (ItemStack. (item-by-id item) (int (or count 1)))
      (apply-components! components)))

  ItemStack
  (->stack [s] s))
