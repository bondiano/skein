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
  "Right-click a block: when it is the ruby block, send a message. Reads
  the world through a pure query (block-at returns data), then returns an
  effect; the event still proceeds."
  [{:keys [player world hit]}]
  (when (and (not (.isClientSide ^Level world))
             (= ruby-block (:block (world/block-at world (.getBlockPos ^BlockHitResult hit)))))
    [[:tell player [:red "Ruby block says: hello from Clojure!"]]]))

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
  "The /ruby about handler: a bit of flavour text back to the source."
  [_]
  [[:reply [:gold "Ruby" [:gray " — the Skein example item, all data."]]]])

(defn init
  "The Fabric `main` entrypoint (see fabric.mod.json). Runs in the phase
  where the registries are still open: content is declared here, the
  logic lives in the vars above."
  []
  (skein/register!
   {:blocks {ruby-block {:strength [3.0 6.0]
                         :requires-tool true
                         :group :building_blocks}}
    :items {ruby-item {:group :ingredients}}})

  ;; Pure event handlers — data in, effects out, hot-reloadable vars.
  (events/on-pure! :player/join #'greet)
  (events/on-pure! :block/use #'on-ruby-use)

  ;; A command tree as data: /ruby give and /ruby about.
  (command/def! :ruby
    {:subs {:give  {:run #'give-ruby}
            :about {:run #'about-ruby}}})

  (reset! initialized? true)
  (println "[skein-example] Hello from Clojure!"))
