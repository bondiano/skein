(ns skein-example-test.l2-test
  "The FP domain layer (L2/L3) against a live dedicated server: the world
  query and effects run on a real ServerLevel, item stacks carry their
  bound data components, and the command-as-data declaration is dispatched
  through the real Brigadier dispatcher — all on the actual server thread.
  Booted and handed over by skein.example.L2ServerTests; the overworld is
  a flat world.

  What is *not* here: player and inventory mutations need a connected
  ServerPlayer, which a headless server has none of — those stay in-game
  (the production smoke test drives a real player path). Everything that a
  server-side world can exercise lives here."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.world :as world]
            [skein.entity :as entity]
            [skein.item :as item]
            [skein.command :as command])
  (:import (java.util.function Supplier)
           (net.minecraft.server MinecraftServer)))

(defonce ^:private the-server (atom nil))

(defn set-server! [^MinecraftServer server] (reset! the-server server))

(defn- server ^MinecraftServer [] @the-server)

(defn- on-server-thread
  "Runs the thunk on the server thread and returns its value (the L2
  effects mutate the world, so they must run there)."
  [f]
  (.get (.submit (server) ^Supplier (reify Supplier (get [_] (f))))))

(defn- overworld [] (.overworld (server)))

(defn- eventually
  "The value of `f` (run on the server thread) once `accept?` takes it, polling
  until a deadline. Some game state only settles on the *next* tick — a freshly
  spawned entity joins the level's entity list there, not inside the spawn call
  — so reading it back in the very next submitted task is a race."
  [accept? f]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [value (on-server-thread f)]
        (if (or (accept? value) (> (System/currentTimeMillis) deadline))
          value
          (do (Thread/sleep 50) (recur)))))))

;;; A block round-trip on the live world: set it, read it back as data.

(deftest set-block-and-read-it-back
  (let [level (overworld)
        pos [0 100 0]]
    (on-server-thread #(world/set-block! level pos {:block :minecraft/gold_block}))
    (is (= {:block :minecraft/gold_block}
           (on-server-thread #(world/block-at level pos)))
        "set-block! then block-at round-trips through the live world")
    (testing "a block with properties"
      (on-server-thread #(world/set-block! level pos {:block :minecraft/oak_stairs
                                                      :props {:facing :north}}))
      (let [back (on-server-thread #(world/block-at level pos))]
        (is (= :minecraft/oak_stairs (:block back)))
        (is (= :north (get-in back [:props :facing])))))))

;;; Spawning an entity returns a data snapshot of the live object, and the
;;; world query sees it.

(deftest spawn-returns-a-snapshot-and-shows-in-a-query
  (let [level (overworld)
        ;; A headless server has no players, so nothing keeps a chunk loaded:
        ;; spawning into an unloaded chunk gives back an entity the world never
        ;; tracks. Load the chunk first, the way a nearby player would.
        _ (on-server-thread #(.getChunk level 0 0))
        snap (on-server-thread #(world/spawn! level :minecraft/pig [0 101 0]))]
    (is (= :minecraft/pig (:type snap)) "spawn! returns the entity as data")
    (is (some? (entity/obj snap)) "the live entity rides in the snapshot meta")
    ;; spawn at a BlockPos centres the entity on the block (x/z + 0.5).
    (is (= [0.5 101.0 0.5] (mapv double (:pos snap))) "snapshot carries the spawn position")
    (is (contains? (eventually #(contains? % :minecraft/pig)
                               #(set (map :type (world/entities level))))
                   :minecraft/pig)
        "the spawned pig shows up in a world entity query once the tick adds it")))

;;; ItemStack <-> data. On a live server the item data-components are bound
;;; (they are not in a bare headless bootstrap), so the full round trip —
;;; item id, count, custom-name text and damage — works here.

(deftest item-stack-round-trips-through-data
  (let [data {:item :minecraft/diamond_sword
              :count 1
              :components {:custom-name [:gold "Excalibur"]
                           :damage 12}}
        back (on-server-thread #(item/stack-> (item/->stack data)))]
    (is (= :minecraft/diamond_sword (:item back)))
    (is (= 1 (:count back)))
    (is (= 12 (get-in back [:components :damage])))
    (is (= {:color :gold :extra ["Excalibur"]}
           (get-in back [:components :custom-name]))
        "the custom-name text round-trips through the hiccup grammar"))
  (testing "a bare id is a single item"
    (is (= {:item :minecraft/diamond :count 1}
           (on-server-thread #(item/stack-> (item/->stack :minecraft/diamond)))))))

;;; The command-as-data declaration (skein.command/def! :ruby, from the mod
;;; entrypoint) is live in the real dispatcher: parsing and running "ruby
;;; about" flows through Brigadier into the effect handler and its :reply
;;; effect, returning SINGLE_SUCCESS.

(deftest command-as-data-dispatches-on-the-live-dispatcher
  (is (some #{:ruby} (command/defined)) "the mod declared /ruby")
  (let [result (on-server-thread
                #(let [src (.withSuppressedOutput (.createCommandSourceStack (server)))]
                   (.execute (.getDispatcher (.getCommands (server))) "ruby about" src)))]
    (is (= 1 result)
        "/ruby about parses and runs its effect handler through the live Brigadier tree")))
