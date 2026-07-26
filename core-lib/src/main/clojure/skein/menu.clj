(ns skein.menu
  "Inventory menus as data: open a chest-style container for a player, its
  contents described as data, and read back what the player left.

      ;; server side — from a command, an event, a packet handler
      (menu/open-chest! player
        {:title \"Ruby Stash\" :rows 3
         :items {13 {:item :skein_example/ruby :count 5}}
         :on-close #'on-stash-closed})

      (defn on-stash-closed [{:keys [contents]}] ...)  ; a vector of item data

  A chest menu reuses the game's own container menu and its client screen, so
  the slots sync between server and client with no extra work — the mod only
  describes the box and its starting contents, and the player sees a normal
  chest GUI. `:rows` is 1–6 (nine slots each). `:items` prefills slots — a map
  `{slot item}` or a sequence indexed from slot 0; each item is item data
  (`:minecraft/diamond`, `{:item :mymod/ruby :count 3}`, or an ItemStack).

  `:on-close` is a var called when the player closes the menu, with
  `{:player <Player> :container <Container> :contents <vector of item data>}` —
  so the mod can react to what the player did (a var, so it hot-reloads).

  The backing container is returned from `open-chest!`; hold onto it to inspect
  or change it while the menu is open (`contents` reads it as data). This is
  server-side: the client screen is the game's own.

  For a bespoke layout or custom sync data on open, drop to the game's
  MenuType / ExtendedMenuType and register a screen — this wraps the common
  chest case, not every container."
  (:require [skein.fx :as fx]
            [skein.item :as item]
            [skein.schema :as schema]
            [skein.text :as text])
  (:import (net.minecraft.server.level ServerPlayer)
           (net.minecraft.world SimpleContainer SimpleMenuProvider)
           (net.minecraft.world.entity.player Inventory Player)
           (net.minecraft.world.inventory ChestMenu MenuConstructor MenuType)
           (net.minecraft.world.item ItemStack)))

(def chest
  "A chest menu described as data."
  [:map {:closed true}
   [:title :any]
   [:rows [:int {:min 1 :max 6}]]
   [:items {:optional true} [:or [:map-of :int :any] [:sequential :any]]]
   [:on-close {:optional true} [:fn {:error/message "must be a var like #'on-close"} var?]]])

;; The game's generic chest menu types, one per row count — accessed lazily so
;; the namespace loads before the registries are frozen.
(def ^:private row-types
  {1 (fn [] MenuType/GENERIC_9x1)
   2 (fn [] MenuType/GENERIC_9x2)
   3 (fn [] MenuType/GENERIC_9x3)
   4 (fn [] MenuType/GENERIC_9x4)
   5 (fn [] MenuType/GENERIC_9x5)
   6 (fn [] MenuType/GENERIC_9x6)})

(defn- ->menu-type
  ^MenuType [rows]
  (if-some [f (row-types rows)]
    (f)
    (throw (ex-info (str "A chest menu has 1–6 rows, got: " rows
                         ". For a bigger or bespoke container, use a custom MenuType.")
                    {:rows rows}))))

(defn- prefill!
  "Writes the items into the container. `items` is a map {slot item} or a
  sequence indexed from slot 0."
  [^SimpleContainer c items]
  (let [pairs (if (map? items) items (map-indexed vector items))
        size (.getContainerSize c)]
    (doseq [[slot data] pairs]
      (when-not (< -1 slot size)
        (throw (ex-info (str "Slot " slot " is out of range for a " size "-slot container.")
                        {:slot slot :size size})))
      (.setItem c (int slot) ^ItemStack (item/->stack data)))
    c))

(defn container
  "Builds the backing container for a chest menu (nine slots per row), prefilled
  from `:items`. Reach for it to prepare a container before opening, or share one
  container across openings."
  ^SimpleContainer [{:keys [rows items]}]
  (let [c (SimpleContainer. (int (* 9 rows)))]
    (when items (prefill! c items))
    c))

(defn contents
  "The container's contents as data: a vector with one entry per slot — item
  data (`{:item ... :count ...}`) or nil for an empty slot."
  [^SimpleContainer c]
  (mapv (fn [i] (item/stack-> (.getItem c (int i))))
        (range (.getContainerSize c))))

(defn- ->server-player
  ^ServerPlayer [target]
  (let [obj (if (map? target) (:skein/obj (meta target)) target)]
    (if (instance? ServerPlayer obj)
      obj
      (throw (ex-info (str "Cannot open a menu for " (pr-str target)
                           " — expected a ServerPlayer (or a player snapshot).")
                      {:target target})))))

(defn- menu-constructor
  "The game's MenuConstructor for the chest: a plain ChestMenu, or — when an
  :on-close var is given — a ChestMenu that calls it with the contents as data
  when the player closes the menu (the var is derefed then, so it hot-reloads)."
  ^MenuConstructor [rows ^SimpleContainer c on-close]
  (reify MenuConstructor
    (createMenu [_ id inv _player]
      (let [^Inventory inv inv]
        (if on-close
          (proxy [ChestMenu] [(->menu-type rows) (int id) inv c (int rows)]
            (removed [^Player p]
              (proxy-super removed p)
              (on-close {:player p :container c :contents (contents c)})))
          (ChestMenu. (->menu-type rows) (int id) inv c (int rows)))))))

(defn open-chest!
  "Opens a chest menu for a player (a ServerPlayer or a player snapshot) and
  returns the backing container. Server side. See the ns docstring for the data."
  ^SimpleContainer [player decl]
  (schema/validate! chest decl "chest menu")
  (let [{:keys [title rows on-close]} decl
        c (container decl)
        provider (SimpleMenuProvider. (menu-constructor rows c on-close) (text/text title))]
    (.openMenu (->server-player player) provider)
    c))

;;; Effect as data (layer B) — thin delegation to open-chest!.

(defmethod fx/fx! :open-chest [_ctx [_ target decl]] (open-chest! target decl))
