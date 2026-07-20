(ns skein.inventory
  "A player's inventory as a vector of stack data.

      (inventory/items player)
      ;=> [{:item :minecraft/diamond :count 3} {:item :minecraft/oak_log :count 12}]

  A pure read: the non-empty slots turned into the same data maps skein.item
  produces. Accepts a live player or a snapshot."
  (:require [skein.item :as item])
  (:import (net.minecraft.server.level ServerPlayer)
           (net.minecraft.world.item ItemStack)))

(defn- ->player ^ServerPlayer [x] (if (map? x) (:skein/obj (meta x)) x))

(defn items
  "The non-empty stacks of the player's inventory, as a vector of stack data
  (see skein.item/stack->)."
  [player]
  (let [inv (.getInventory (->player player))]
    (into []
          (comp (map (fn [i] (.getItem inv (int i))))
                (remove (fn [^ItemStack s] (.isEmpty s)))
                (map item/stack->))
          (range (.getContainerSize inv)))))
