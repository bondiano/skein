(ns mymod.core
  "The mod's entry point, written in Skein's FP layer: content is declared
  as data, event handlers are pure functions of the event's data that
  return a vector of effects, and every handler sits behind a var — redefine
  it from the REPL and the live game changes on the next call.

  The FP namespaces you use are required here so AOT bakes them into the mod
  jar (core-lib ships as sources, so a production REPL only finds what the mod
  compiled in)."
  (:require [skein.core :as skein]
            [skein.events :as events]
            [skein.text]
            [skein.player]))

;; Content ids as data — one source of truth, shared by the declaration and
;; the handlers. A bare keyword lands in your mod's namespace.
(def gem :mymod/gem)

;;; Pure handlers. A handler takes the event's data map and returns a vector
;;; of effects (see skein.fx) — no game types in the body, no mutation. Call
;;; one with a plain map to test it; redefine it from the REPL to change the
;;; live game.

(defn greet
  "A player finished joining: welcome them. The message is a skein.text
  hiccup form carried by a :tell effect."
  [{:keys [player]}]
  [[:tell player [:aqua "Hello from " [:gold "My Mod"] "! "
                  "Redefine this handler from the REPL."]]])

(defn init
  "The Fabric `main` entrypoint (see fabric.mod.json). Runs while the
  registries are still open — declare content here, keep logic in the vars
  above."
  []
  ;; Declare content as data. The registries freeze right after startup, so
  ;; register! only works from here (adding a new item later needs a restart).
  (skein/register!
   {:items {gem {:group :ingredients}}})

  ;; Register the var (#'), not the fn — that is what makes it hot-reloadable.
  (events/on-pure! :player/join #'greet)

  (println "[mymod] initialized"))
