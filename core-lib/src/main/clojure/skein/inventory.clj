(ns skein.inventory
  "A player's inventory as a vector of stack data.

      (inventory/items player)
      ;=> [{:item :minecraft/diamond :count 3} {:item :minecraft/oak_log :count 12}]

  A pure read: the non-empty slots turned into the same data maps skein.item
  produces. Accepts a live player or a snapshot."
  (:require [skein.item :as item])
  (:import (net.minecraft.server.level ServerPlayer)))

(defn- ->player ^ServerPlayer [x] (if (map? x) (:skein/obj (meta x)) x))

(defn items
  "The non-empty stacks of the player's inventory, as a vector of stack data
  (see skein.item/stack->)."
  [player]
  (let [inv (.getInventory (->player player))]
    (into []
          (comp (map #(.getItem inv (int %)))
                (remove #(.isEmpty %))
                (map item/stack->))
          (range (.getContainerSize inv)))))
