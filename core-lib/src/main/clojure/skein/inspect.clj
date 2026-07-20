(ns skein.inspect
  "REPL inspection of live game state — the tools you reach for at the nREPL to
  see what the game is doing, without printing from handlers.

  Two things:

  1. A `tap>` buffer. `tap>` is Clojure's channel for handing a value to a tool;
     this namespace keeps the most recent tapped values in a bounded ring you can
     read back from the REPL:

         (require '[skein.inspect :as inspect])
         (tap> {:hit pos})           ; from anywhere, even a tick handler
         (inspect/last-tap)          ; => {:n 41 :ms .. :value {:hit [10 64 10]}}
         (inspect/taps :values)      ; => the buffered values, oldest first

     The buffer coexists with Portal or Reveal — those register their own tap, so
     `tap>` feeds the inspector window and this buffer at once. Add Portal in the
     dev REPL with `add-lib`, then `(add-tap (requiring-resolve 'portal.api/submit))`
     and `tap>` streams to it live; this buffer is the always-on, dependency-free
     fallback.

  2. Game-state dumps. `dump`, `entities-around` and `blocks-around` read a
     consistent frame on the game thread (reading live objects from an eval
     thread races the tick) and return plain data — snapshots, block maps — ready
     to `tap>`, filter, or eyeball.

  A player argument is a live ServerPlayer, a player snapshot, or a name string
  (looked up on the server)."
  (:require [skein.interop :as interop]
            [skein.inventory :as inventory]
            [skein.player :as player]
            [skein.pos :as pos]
            [skein.world :as world])
  (:import (net.minecraft.server.level ServerLevel ServerPlayer)))

;;; tap> buffer — a bounded ring of the most recent tapped values.

(def ^:private default-capacity 128)

(defonce ^:private buffer (atom {:capacity default-capacity :seq 0 :items []}))

(defn- capture [v]
  (swap! buffer
         (fn [{:keys [capacity seq items] :as b}]
           (let [items' (conj items {:n seq :ms (System/currentTimeMillis) :value v})]
             (assoc b
                    :seq (inc seq)
                    :items (if (> (count items') capacity)
                             (subvec items' (- (count items') capacity))
                             items'))))))

(defonce ^:private tap-fn (atom nil))

(defn install-tap!
  "Start capturing `tap>` values into the buffer. Idempotent, and additive — it
  does not replace a Portal/Reveal tap. Called automatically when this namespace
  loads; call it again after `uninstall-tap!`."
  []
  (when-not @tap-fn
    (add-tap capture)
    (reset! tap-fn capture))
  :installed)

(defn uninstall-tap!
  "Stop capturing `tap>` values (leaves any other taps alone)."
  []
  (when-some [f @tap-fn]
    (remove-tap f)
    (reset! tap-fn nil))
  :uninstalled)

(defn taps
  "The buffered `tap>` entries, oldest first, each `{:n :ms :value}`. With
  `:values`, just the values."
  ([] (:items @buffer))
  ([k] (if (= k :values) (mapv :value (:items @buffer)) (:items @buffer))))

(defn last-tap
  "The most recent buffered `tap>` entry (nil if none)."
  []
  (peek (:items @buffer)))

(defn clear-taps!
  "Empty the buffer."
  []
  (swap! buffer assoc :items [])
  :cleared)

(defn capacity!
  "Resize the buffer, keeping the most recent `n` entries."
  [n]
  (swap! buffer (fn [b] (-> b
                            (assoc :capacity (int n))
                            (update :items #(if (> (count %) n) (subvec % (- (count %) n)) %)))))
  n)

;; tap> just works after requiring this namespace.
(defonce ^:private _auto-install (install-tap!))

;;; Resolving a player argument

(defn- ->player ^ServerPlayer [x]
  (cond
    (instance? ServerPlayer x) x
    (map? x) (or (player/obj x)
                 (throw (ex-info "That player snapshot carries no live object (:skein/obj)" {:snapshot x})))
    (string? x) (or (interop/player x)
                    (throw (ex-info (str "No player named " (pr-str x) " is online") {:name x})))
    :else (throw (ex-info (str "Expected a ServerPlayer, a player snapshot, or a name string, got: " (pr-str x))
                          {:value x}))))

(defn- dist2 [[ax ay az] [bx by bz]]
  (let [dx (- ax bx) dy (- ay by) dz (- az bz)]
    (+ (* dx dx) (* dy dy) (* dz dz))))

;;; Dumps — the *-impl variants assume they already run on the game thread, so
;;; the public entry points wrap once and never nest on-server dispatch.

(defn- entities-around-impl [^ServerPlayer pl ^ServerLevel level radius]
  (let [origin (pos/vec3-> (.position pl))
        self (.getUUID pl)
        r2 (* radius radius)]
    (into []
          (comp (remove #(= (:uuid %) self))
                (filter #(<= (dist2 (:pos %) origin) r2)))
          (world/entities level))))

(defn- blocks-around-impl [^ServerPlayer pl ^ServerLevel level radius]
  (let [[px py pz] (mapv long (pos/vec3-> (.position pl)))]
    (into {}
          (keep (fn [c]
                  (let [b (world/block-at level c)]
                    (when-not (= (:block b) :minecraft/air) [c b]))))
          (for [dx (range (- radius) (inc radius))
                dy (range (- radius) (inc radius))
                dz (range (- radius) (inc radius))]
            [(+ px dx) (+ py dy) (+ pz dz)]))))

(defn entities-around
  "Snapshots of the entities within `radius` blocks of the player (default 16,
  the player excluded), read on the game thread."
  ([player] (entities-around player 16))
  ([player radius]
   (interop/on-server
    (let [pl (->player player)]
      (entities-around-impl pl ^ServerLevel (.level pl) radius)))))

(defn blocks-around
  "A map of `[x y z]` -> block data for the non-air blocks in a cube of `radius`
  (default 2) around the player, read on the game thread. Keep `radius` small —
  the cube is (2·radius+1)³ blocks."
  ([player] (blocks-around player 2))
  ([player radius]
   (interop/on-server
    (let [pl (->player player)]
      (blocks-around-impl pl ^ServerLevel (.level pl) radius)))))

(defn dump
  "A one-call snapshot for the REPL, read on the game thread: the player, their
  inventory, the block they stand on, and nearby entities. `tap>` it or eyeball
  it. Options: `:radius` for the entity scan (default 16)."
  ([player] (dump player nil))
  ([player {:keys [radius] :or {radius 16}}]
   (interop/on-server
    (let [pl (->player player)
          level ^ServerLevel (.level pl)
          [px py pz] (mapv long (pos/vec3-> (.position pl)))]
      {:player (player/snapshot pl)
       :inventory (inventory/items pl)
       :standing-on (world/block-at level [px (dec py) pz])
       :entities (entities-around-impl pl level radius)}))))
