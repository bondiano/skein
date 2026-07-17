(ns skein-example-test.content-test
  "Game content in the headless Fabric env: the example mod registers a
  block and an item through the core-lib registry DSL and hooks event
  handlers as vars. The harness reproduces the production phase order —
  vanilla bootstrap first, then the mod entrypoint registers into the
  still-open registries — and everything is asserted through the game's
  own registry lookups plus the Fabric event invokers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.core :as skein]
            [skein.events :as events])
  (:import (clojure.lang ExceptionInfo)
           (java.lang.reflect Method)
           (net.fabricmc.fabric.api.event.lifecycle.v1 ServerTickEvents)
           (net.minecraft.core.registries BuiltInRegistries)
           (net.minecraft.resources Identifier)
           (net.minecraft.world.item BlockItem)))

(deftest registry-dsl-registers-block-item-and-block-item
  (is (.containsKey BuiltInRegistries/BLOCK (Identifier/parse "skein_example:ruby_block"))
      "ruby_block in BLOCK registry")
  (is (.containsKey BuiltInRegistries/ITEM (Identifier/parse "skein_example:ruby"))
      "ruby in ITEM registry")
  (is (instance? BlockItem (.getValue BuiltInRegistries/ITEM (Identifier/parse "skein_example:ruby_block")))
      "auto-registered BlockItem for the block")
  (testing "introspection"
    (let [{:keys [blocks items]} (skein/registered)]
      (is (some #{:skein_example/ruby_block} blocks))
      (is (some #{:skein_example/ruby} items)))))

;;; The event machinery: on! attaches a var; the Fabric event invoker
;;; calls it. Re-defing the var changes the behaviour without another
;;; registration — hot reload.

(def ^:private tick-events (atom []))

(defn- test-tick-handler [server]
  (swap! tick-events conj [:original server]))

(defn- fire-tick-event!
  "Pokes the END_SERVER_TICK invoker the way the server would at the end
  of a tick."
  []
  (.onEndTick (.invoker ServerTickEvents/END_SERVER_TICK) nil))

(deftest event-handler-var-is-called-and-hot-reloadable
  (events/on! :server/tick-end #'test-tick-handler)
  ;; Registering the same pair again is a no-op (otherwise the invoker
  ;; would call the handler twice per fire).
  (events/on! :server/tick-end #'test-tick-handler)
  (reset! tick-events [])
  (fire-tick-event!)
  (testing "after the first fire"
    (is (= 1 (count @tick-events)) "duplicate on! must not double-register")
    (is (= :original (ffirst @tick-events))))
  (alter-var-root #'test-tick-handler
                  (constantly (fn [server] (swap! tick-events conj [:redefined server]))))
  (fire-tick-event!)
  (testing "after re-def"
    (is (= 2 (count @tick-events)))
    (is (= :redefined (first (second @tick-events)))
        "re-defed var picked up without re-registration"))
  (is (some #{:server/tick-end} (map first (events/handlers)))))

(deftest event-errors-are-actionable
  (is (thrown-with-msg? ExceptionInfo #"Supported:"
                        (events/on! :nope/nope #'test-tick-handler))
      "unknown event lists the supported ones")
  (is (thrown-with-msg? ExceptionInfo #"hot-reloadable"
                        (events/on! :server/tick-end test-tick-handler))
      "a fn value instead of a var explains why a var is required"))

;;; The registry after the freeze: logic reloads, content does not.

(deftest frozen-registry-fails-fast-but-re-eval-is-safe
  ;; In the game fabric-api freezes the registries after all mods init
  ;; (and datapack loading binds the tags). The headless test has neither
  ;; phase — bind the requested tags to empty and freeze ourselves.
  (.bindAllTagsToEmpty BuiltInRegistries/ITEM)
  (.freeze BuiltInRegistries/ITEM)
  (is (thrown-with-msg? ExceptionInfo #"restart the game"
                        (skein/register! {:items {:skein_example/late-item {}}}))
      "registering a new id after freeze must throw")
  ;; A changed or identical declaration for an existing id must be a
  ;; skip (warning/no-op), not a crash — re-evaluating a namespace from
  ;; the REPL hits this path.
  (is (= {} (:items (skein/register! {:items {:skein_example/ruby {:stacks-to 16}}})))
      "changed declaration of a registered id is a warning + skip")
  (is (= {} (:items (skein/register! {:items {:skein_example/ruby {:group :ingredients}}})))
      "identical declaration is a silent no-op"))

;;; The mixin: declared with defmixin, class generated at build time.
;;; Two angles here — the SkeinHooks -> var -> fn chain the generated
;;; bytecode drives (callable headlessly), and the build artifacts
;;; themselves: the config landed in fabric.mod.json and the mixin
;;; applies to the real MinecraftServer class. (The live-tick proof
;;; runs in the production smoke test against a real server.)

(deftest mixin-hook-calls-clojure-handler-through-var
  ;; The first call loads the handler ns itself — just like in the game,
  ;; where the hook fires before any explicit require. The hook is Java
  ;; varargs: from Clojure the arguments go in as a ready-made array.
  (skein.runtime.SkeinHooks/call "skein-example.mixin/server-mixin-tickServer"
                                 (object-array [nil nil nil]))
  (let [counter @(ns-resolve 'skein-example.mixin 'tick-count)
        before @counter]
    (skein.runtime.SkeinHooks/call "skein-example.mixin/server-mixin-tickServer"
                                   (object-array [nil nil nil]))
    (is (= (inc before) @counter) "SkeinHooks must invoke the mixin handler var")))

(deftest generated-mixin-is-registered-and-applies
  ;; The whole build-time pipeline in one probe: defmixin registered the
  ;; declaration, the pipeline generated the class + config, the plugin
  ;; patched fabric.mod.json, and Sponge merged the handler into the
  ;; real MinecraftServer class on load. Loading the target class runs
  ;; the transformer — with defaultRequire=1 a failed apply would throw
  ;; right here. The merged handler keeps its skein$tickServer name
  ;; inside Sponge's decoration; its presence is the positive proof the
  ;; whole build-time chain ran (a missing config would just no-op).
  (let [method-names (mapv (fn [^Method m] (.getName m))
                           (.getDeclaredMethods (Class/forName "net.minecraft.server.MinecraftServer")))]
    (is (some #(str/includes? % "skein$tickServer") method-names)
        (str "generated injector merged into MinecraftServer; declared methods: "
             (pr-str method-names)))))
