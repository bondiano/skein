(ns skein-example-test.state-test
  "State and attachments against the live dedicated server: the mod's state is
  bound to the open world, its value reaches the save file on disk, and data
  attached to an entity or a chunk survives a read-back through the real game
  objects. Booted and handed over by skein.example.L2ServerTests.

  Everything here runs against the world the L2 suite opened, so the assertions
  are about real files under its world directory — not a stand-in."
  (:require [clojure.test :refer [deftest is testing]]
            [skein-example.core :as example]
            [skein.attach :as attach]
            [skein.codec :as codec]
            [skein.state :as state]
            [skein.world :as world])
  (:import (java.util.function Supplier)
           (net.minecraft.nbt NbtAccounter NbtIo)
           (net.minecraft.server MinecraftServer)
           (net.minecraft.world.level.storage LevelResource)))

(defonce ^:private the-server (atom nil))

(defn set-server! [^MinecraftServer server] (reset! the-server server))

(defn- server ^MinecraftServer [] @the-server)

(defn- on-server-thread
  "Runs the thunk on the server thread and returns its value."
  [f]
  (.get (.submit (server) ^Supplier (reify Supplier (get [_] (f))))))

(defn- overworld [] (.overworld (server)))

(defn- saved-value
  "The value stored on disk for a state id, read back from its save file the
  way the game reads it: the compressed NBT file, then the codec."
  [id schema]
  (let [file (-> (.getWorldPath (server) LevelResource/DATA)
                 (.resolve (namespace id))
                 (.resolve (str (name id) ".dat")))]
    (when (.exists (.toFile file))
      (let [root (NbtIo/readCompressed file (NbtAccounter/unlimitedHeap))]
        (codec/nbt-> schema (.get root "data"))))))

;;; State

(deftest the-mods-state-is-bound-to-the-open-world
  (let [entry (get (state/declared) :skein_example/ruby_taps)]
    (is (true? (:persist? entry)) "the mod declared it as persistent")
    (is (true? (:saved? entry)) "and it is bound to the world the server opened")))

(deftest a-state-value-reaches-the-world-save
  (reset! example/ruby-taps 7)
  (on-server-thread state/flush!)
  (is (= 7 (saved-value :skein_example/ruby_taps :int))
      "the value on disk is what the atom held when the save ran")
  (testing "a later change reaches the same file"
    (swap! example/ruby-taps + 5)
    (on-server-thread state/flush!)
    (is (= 12 (saved-value :skein_example/ruby_taps :int)))))

(deftest a-state-declared-while-a-world-is-open-binds-immediately
  ;; The REPL case: a `defstate` evaluated mid-session must not wait for the
  ;; next server start to become part of the save.
  (let [id :skein_example/repl_declared
        declared (state/define! id {:schema [:map-of :keyword :int] :init {} :persist? true})]
    (is (true? (:saved? (get (state/declared) id))))
    (reset! declared {:a 1 :b 2})
    (on-server-thread state/flush!)
    (is (= {:a 1 :b 2} (saved-value id [:map-of :keyword :int])))))

(deftest re-declaring-a-state-keeps-the-live-value
  (let [before @example/ruby-taps
        again (state/define! :skein_example/ruby_taps {:id :skein_example/ruby_taps
                                                       :schema :int :init 0 :persist? true})]
    (is (identical? example/ruby-taps again) "the same atom, so handlers keep their data")
    (is (= before @again))))

;;; Attachments

(deftest data-attaches-to-a-live-entity
  (let [pig (on-server-thread #(world/spawn! (overworld) :minecraft/pig [0 101 0]))]
    (is (= 0 (attach/attached pig example/player-taps)) "an unset attachment reads as its :init")
    (attach/attach! pig example/player-taps 4)
    (is (= 4 (attach/attached pig example/player-taps)))
    (testing "update! applies a function to what is there"
      (is (= 6 (attach/update! pig example/player-taps + 2)))
      (is (= 6 (attach/attached pig example/player-taps))))
    (testing "detach! removes it and the value falls back to :init"
      (attach/detach! pig example/player-taps)
      (is (= 0 (attach/attached pig example/player-taps))))))

(deftest data-attaches-to-a-chunk
  (let [chunk (on-server-thread #(attach/chunk-at (overworld) [0 101 0]))]
    (attach/attach! chunk example/player-taps 3)
    (is (= 3 (attach/attached chunk example/player-taps)))
    (attach/detach! chunk example/player-taps)))

(deftest an-attached-value-is-checked-against-its-schema
  (let [pig (on-server-thread #(world/spawn! (overworld) :minecraft/pig [0 101 0]))]
    (is (thrown? Exception (attach/attach! pig example/player-taps "four"))
        "a value of the wrong shape is refused at the boundary, not at save time")))
