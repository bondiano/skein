(ns skein.entity
  "Entities as lazy snapshots plus `!`-mutations.

      (entity/snapshot e)
      ;=> {:type :minecraft/zombie :uuid #uuid\"..\" :pos [10.0 64.0 10.0]}
      ;   with the live Entity in the metadata under :skein/obj

      (entity/obj snap)                 ; the live object back
      (entity/damage! e 4.0)
      (entity/velocity! e [0 1 0])
      (entity/teleport! e [10 80 10])

  A snapshot is an ordinary map: `=`, `pr`, `select-keys` and destructuring work
  on it as plain data. The live object rides in the metadata, not as a key, so
  it never leaks into comparisons or serialization."
  (:require [skein.coerce :as coerce]
            [skein.fx :as fx]
            [skein.id :as id]
            [skein.pos :as pos])
  (:import (net.minecraft.world.entity Entity EntityType LivingEntity)
           (net.minecraft.world.phys Vec3)))

(defn snapshot
  "A data snapshot of an entity, with the live object in the :skein/obj meta."
  [^Entity e]
  (with-meta
    {:type (id/id->kw (EntityType/getKey (.getType e)))
     :uuid (.getUUID e)
     :pos (pos/vec3-> (.position e))}
    {:skein/obj e}))

(defn obj
  "The live Entity behind a snapshot (nil if it carries none)."
  ^Entity [snap]
  (:skein/obj (meta snap)))

(defn- ->entity
  "A live Entity from either a snapshot or the object itself — so both a REPL
  snapshot and a raw entity work as an argument."
  ^Entity [x]
  (if (map? x) (obj x) x))

(defn damage!
  "Deals `amount` of generic damage to a living entity (a snapshot or object)."
  [target amount]
  (let [^LivingEntity e (->entity target)]
    (.hurtServer e (.level e) (.generic (.damageSources e)) (float amount))))

(defn velocity!
  "Sets the entity's velocity to the vector `v` (blocks per tick), and marks it
  so the change is sent to clients."
  [target v]
  (let [^Entity e (->entity target)]
    (.setDeltaMovement e (coerce/->vec3 v))
    (set! (.-hurtMarked e) true)))

(defn teleport!
  "Moves the entity to pos."
  [target p]
  (let [^Entity e (->entity target)
        ^Vec3 v (coerce/->vec3 p)]
    (.teleportTo e (.x v) (.y v) (.z v))))

;;; Effects as data (layer B) — the target rides in the effect, ctx is unused.

(defmethod fx/fx! :damage   [_ [_ target amount]] (damage! target amount))
(defmethod fx/fx! :velocity [_ [_ target v]]      (velocity! target v))
(defmethod fx/fx! :teleport [_ [_ target p]]      (teleport! target p))
