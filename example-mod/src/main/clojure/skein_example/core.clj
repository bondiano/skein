(ns skein-example.core
  "The Skein demo mod: entrypoint + registry DSL + event handlers.
  Doubles as the integration test in CI; the REPL session lives in the
  README."
  (:require [skein.core :as skein]
            [skein.events :as events]
            ;; Not used by init directly, but AOT bakes the ns into the jar
            ;; — otherwise (require 'skein.interop) in a production REPL
            ;; would find no sources (core-lib compiles into the mod, its
            ;; jar is not shipped).
            [skein.interop :as interop])
  (:import (net.minecraft.network.chat Component)
           (net.minecraft.world.entity.player Player)
           (net.minecraft.world.level Level)
           (net.minecraft.world.phys BlockHitResult)))

(def initialized?
  "Flag for the integration test: true after the loader ran the
  entrypoint."
  (atom false))

;;; Event handlers. Vars (#') are registered, not values: re-defing any
;;; handler from the REPL changes the behaviour of the live game
;;; immediately.

(defn greet
  "Greets a joining player — try redefining this from the REPL."
  [^Player player]
  (.sendSystemMessage player
                      (Component/literal "[skein-example] Welcome! This handler is plain Clojure — redefine it from the REPL.")))

(defn on-ruby-use
  "Right-click on the ruby block → a message. nil = :pass (the event
  proceeds)."
  [^Player player ^Level world _hand ^BlockHitResult hit]
  (when-not (.isClientSide world)
    (let [block (.getBlock (.getBlockState world (.getBlockPos hit)))]
      (when (identical? (skein/block :skein_example/ruby_block) block)
        (.sendSystemMessage player (Component/literal "Ruby block says: hello from Clojure!")))))
  nil)

(defn init
  "The Fabric `main` entrypoint (see fabric.mod.json). Runs in the phase
  where the registries are still open: content is declared here, the
  logic lives in the vars above."
  []
  (skein/register!
   {:blocks {:skein_example/ruby_block {:strength [3.0 6.0]
                                        :requires-tool true
                                        :group :building_blocks}}
    :items {:skein_example/ruby {:group :ingredients}}})

  (events/on! :player/join #'greet)
  (events/on! :block/use #'on-ruby-use)

  (reset! initialized? true)
  (println "[skein-example] Hello from Clojure!"))
