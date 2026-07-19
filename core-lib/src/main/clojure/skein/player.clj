(ns skein.player
  "Players as lazy snapshots plus `!`-mutations.

      (player/snapshot p)
      ;=> {:name \"Steve\" :uuid #uuid\"..\" :pos [..] :health 20.0}
      ;   with the live ServerPlayer in the :skein/obj meta

      (player/give! p {:item :minecraft/diamond :count 3})
      (player/tell! p [:green \"done\"])
      (player/teleport! p [10 80 10])

  Same snapshot convention as skein.entity — plain-data map, live object in the
  metadata. The give/tell effects are also available as data through skein.fx:
  `[:give player stack]` and `[:tell player msg]`."
  (:require [skein.entity :as entity]
            [skein.fx :as fx]
            [skein.item :as item]
            [skein.pos :as pos]
            [skein.text :as text])
  (:import (net.minecraft.server.level ServerPlayer)
           (net.minecraft.world.item ItemStack)))

(defn snapshot
  "A data snapshot of a player, with the live object in the :skein/obj meta."
  [^ServerPlayer p]
  (with-meta
    {:name (.getString (.getName p))
     :uuid (.getUUID p)
     :pos (pos/vec3-> (.position p))
     :health (.getHealth p)}
    {:skein/obj p}))

(defn obj
  "The live ServerPlayer behind a snapshot (nil if it carries none)."
  ^ServerPlayer [snap]
  (:skein/obj (meta snap)))

(defn- ->player ^ServerPlayer [x] (if (map? x) (obj x) x))

(defn give!
  "Puts a stack (item id or data map, see skein.item) into the player's
  inventory, dropping whatever does not fit at their feet."
  [player stack]
  (let [^ServerPlayer p (->player player)
        ^ItemStack s (item/->stack stack)]
    (.add (.getInventory p) s)
    (when-not (.isEmpty s)
      (.drop p s false false))))

(defn tell!
  "Sends a chat message (a skein.text form) to the player."
  [player msg]
  (.sendSystemMessage (->player player) (text/text msg)))

(defn teleport!
  "Moves the player to pos (see skein.entity/teleport!)."
  [player p]
  (entity/teleport! (->player player) p))

;;; Effects as data (layer B) — the player rides in the effect, ctx is unused.

(defmethod fx/fx! :give [_ [_ player stack]] (give! player stack))
(defmethod fx/fx! :tell [_ [_ player msg]]   (tell! player msg))
