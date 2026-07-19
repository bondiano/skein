(ns skein.world
  "The world: pure queries and `!`-effects on a ServerLevel.

      (world/block-at level [10 64 10])     ;=> {:block :minecraft/stone}
      (world/set-block! level [10 64 10] {:block :minecraft/oak_stairs
                                          :props {:facing :north}})
      (world/spawn! level :minecraft/zombie [10 64 10])

  Reads return data (a block map, entity snapshots); the `!`-effects mutate and
  must run on the game thread. The same effects are available as data through
  skein.fx: `[:set-block pos block]` and `[:spawn type pos]`."
  (:require [skein.block :as block]
            [skein.coerce :as coerce]
            [skein.entity :as entity]
            [skein.fx :as fx]
            [skein.id :as id])
  (:import (net.minecraft.core.registries BuiltInRegistries)
           (net.minecraft.server.level ServerLevel)
           (net.minecraft.world.entity EntitySpawnReason EntityType)
           (net.minecraft.world.level.block Block)))

;;; Query

(defn block-at
  "The block at pos as data (see skein.block)."
  [^ServerLevel world pos]
  (block/blockstate-> (.getBlockState world (coerce/->blockpos pos))))

(defn entities
  "Snapshots (see skein.entity) of every entity in the world."
  [^ServerLevel world]
  (mapv entity/snapshot (.getAllEntities world)))

;;; Effects

(defn- entity-type-by-id ^EntityType [type-id]
  (let [identifier (id/id type-id)
        holder (.get BuiltInRegistries/ENTITY_TYPE identifier)]
    (if (.isPresent holder)
      (.value (.get holder))
      (throw (ex-info (str "No entity type registered for " (pr-str type-id) " (" identifier ")")
                      {:entity-type type-id})))))

(defn set-block!
  "Sets the block at pos from block data. Flags default to a full update; pass
  an int (see Block/UPDATE_*) to override. Returns true when the block changed."
  ([world pos block-data] (set-block! world pos block-data Block/UPDATE_ALL))
  ([^ServerLevel world pos block-data flags]
   (.setBlock world (coerce/->blockpos pos) (block/->blockstate block-data) (int flags))))

(defn spawn!
  "Spawns an entity of the given type id at pos; returns its snapshot (nil if
  the spawn was refused)."
  [^ServerLevel world type-id pos]
  (when-some [e (.spawn (entity-type-by-id type-id) world (coerce/->blockpos pos) EntitySpawnReason/COMMAND)]
    (entity/snapshot e)))

;;; Effects as data (layer B) — thin delegation to the `!`-functions above.

(defmethod fx/fx! :set-block [world [_ pos block-data]] (set-block! world pos block-data))
(defmethod fx/fx! :spawn     [world [_ type-id pos]]     (spawn! world type-id pos))
