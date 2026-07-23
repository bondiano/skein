(ns skein-example.core
  "The Skein demo mod, written in the idiomatic FP layer: content is
  declared as data, event handlers are *pure* functions of the event's
  data that return a vector of effects, and a command is described as
  data too. Every handler sits behind a var — redefine it from the REPL
  and the live game changes on the next call. Doubles as the integration
  test in CI; the REPL session lives in the README.

  The domain namespaces are required so AOT bakes them into the mod jar:
  core-lib ships as sources (its jar is not shipped), so a production
  REPL only finds skein.text / skein.world / skein.player / skein.item if
  the mod compiled them in."
  (:require [skein.core :as skein]
            [skein.events :as events]
            [skein.command :as command]
            [skein.world :as world]
            [skein.attach :as attach]
            [skein.net :as net]
            [skein.state :as state]
            [skein.text]
            [skein.player]
            [skein.item]
            [skein.interop])
  (:import (net.minecraft.commands CommandSourceStack)
           (net.minecraft.world.level Level)
           (net.minecraft.world.phys BlockHitResult)))

(def initialized?
  "Flag for the integration test: true after the loader ran the
  entrypoint."
  (atom false))

;; The content ids as data — shared by the declaration and the handlers,
;; so there is a single source of truth for a name.
(def ruby-block :skein_example/ruby_block)
(def ruby-item  :skein_example/ruby)

;; Content is declared at the top level so it registers when the entrypoint ns
;; loads (the registries-open phase), and — being data — the build sees it too:
;; AOT validates the declaration (a typo is a build error) and the datagen fork
;; reads the ids to check skein-data.edn against them. Logic (events, commands)
;; stays in `init`, where a running server exists.
(skein/register!
 {:blocks {ruby-block {:strength [3.0 6.0]
                       :requires-tool true
                       :group :building_blocks}}
  :items {ruby-item {:group :ingredients}}})

;; State the mod keeps. The world-wide tally is a `defstate`: an atom that
;; survives a REPL reload of this namespace and is written into the world save.
;; The per-player tally is an attachment — it belongs to one player, so the game
;; saves it in that player's own data and carries it through a respawn.
(state/defstate ruby-taps
  {:id :skein_example/ruby_taps
   :schema :int
   :init 0
   :persist? true})

(def player-taps
  "The id of the per-player tally attached to each player."
  :skein_example/player_ruby_taps)

(def taps-packet
  "The id of the packet that carries both tallies to the client."
  :skein_example/ruby_taps_update)

(defn declare-packets!
  "Declares the mod's packets. Called from both entrypoints — a declaration is
  idempotent, so neither side depends on the other having run first."
  []
  (net/define! taps-packet
    {:schema [:map [:mine :int] [:world :int]] :dir :s2c}))

;;; Pure handlers (style B). A handler takes the event's data map and
;;; returns a vector of effects (see skein.fx) — no game types in the
;;; body, no mutation. Call one with a plain map to test it, redefine it
;;; from the REPL to change the live game.

(defn greet
  "A player finished joining: welcome them. The message is a skein.text
  hiccup form carried by a :tell effect."
  [{:keys [player]}]
  [[:tell player [:aqua "Welcome to Skein! "
                  [:gold "This handler is pure Clojure"]
                  " — redefine it from the REPL."]]])

(defn on-ruby-use
  "Right-click a block: when it is the ruby block, count the tap and say so.
  Reads the world and both tallies through pure queries, then returns the
  effects — the counters are updated as data (`:swap-state`, `:attach`), so the
  handler stays a plain data -> effects function."
  [{:keys [player world hit]}]
  (when (and (not (.isClientSide ^Level world))
             (= ruby-block (:block (world/block-at world (.getBlockPos ^BlockHitResult hit)))))
    (let [mine (inc (attach/attached player player-taps))
          here (inc @ruby-taps)]
      [[:swap-state :skein_example/ruby_taps inc]
       [:attach player player-taps mine]
       ;; The same numbers as data on the wire: the client gets them as a map
       ;; and decides how to show them (see skein-example.client).
       [:send-packet player taps-packet {:mine mine :world here}]
       [:tell player [:red "Ruby block says: hello from Clojure! "
                      [:gray (str "You have tapped it " mine "×, this world " here "×.")]]]])))

;;; A command described as data. The :run handlers are vars (#') so
;;; redefining one hot-reloads the command's behaviour; they return the
;;; same kind of effect vector the event handlers do, run through
;;; skein.fx against the command source (:reply and :give both target it).

(defn give-ruby
  "The /ruby give handler: hand the executing player a named ruby, or
  explain that only a player can hold one."
  [{:keys [source]}]
  (if-some [player (.getPlayer ^CommandSourceStack source)]
    [[:give player {:item ruby-item :count 1
                    :components {:custom-name [:red "Example Ruby"]}}]
     [:reply [:green "Here is a ruby."]]]
    [[:reply [:red "Only a player can hold a ruby."]]]))

(defn about-ruby
  "The /ruby about handler: flavour text plus what the mod currently holds —
  the world tally from the saved state, the caller's own from their attachment."
  [{:keys [source]}]
  (let [mine (some-> (.getPlayer ^CommandSourceStack source) (attach/attached player-taps))]
    [[:reply [:gold "Ruby" [:gray " — the Skein example item, all data."]
              [:white (str " Tapped " @ruby-taps "× in this world"
                           (when mine (str ", " mine "× by you"))
                           ".")]]]]))

(defn init
  "The Fabric `main` entrypoint (see fabric.mod.json). Content is declared at
  the top level (above); here we wire the logic — pure event handlers and a
  command tree, all behind hot-reloadable vars."
  []
  ;; The per-player tally. Attachment types are registered against the game, so
  ;; they are declared here rather than at the top level (where AOT would see
  ;; them); the world-wide `defstate` above needs no such care.
  (attach/define! player-taps
    {:schema :int :init 0 :persist? true :copy-on-death? true})

  ;; The packet the tallies travel on. Declared here and again from the client
  ;; entrypoint, so neither side has to run first.
  (declare-packets!)

  ;; Pure event handlers — data in, effects out, hot-reloadable vars.
  (events/on-pure! :player/join #'greet)
  (events/on-pure! :block/use #'on-ruby-use)

  ;; A command tree as data: /ruby give and /ruby about.
  (command/def! :ruby
    {:subs {:give  {:run #'give-ruby}
            :about {:run #'about-ruby}}})

  (reset! initialized? true)
  (println "[skein-example] Hello from Clojure!"))
