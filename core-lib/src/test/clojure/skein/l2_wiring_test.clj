(ns skein.l2-wiring-test
  "Loading the domain namespaces links their code against the real Minecraft and
  Brigadier types (a compile-time smoke test of the L2 API surface) and, as a
  side effect, registers their built-in effects into skein.fx. This test forces
  that load and checks the wiring."
  (:require [clojure.test :refer [deftest is]]
            [skein.fx :as fx]
            ;; force-load every L2 namespace
            [skein.block]
            [skein.item]
            [skein.world]
            [skein.entity]
            [skein.player]
            [skein.inventory]
            [skein.schedule]
            [skein.command]))

(deftest domain-effects-are-registered
  (let [known (set (fx/known-effects))]
    (doseq [effect [:set-block :spawn :damage :velocity :teleport :give :tell :reply]]
      (is (contains? known effect) (str effect " should be registered")))))
