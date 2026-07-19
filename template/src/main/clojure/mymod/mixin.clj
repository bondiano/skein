(ns mymod.mixin
  "Mixin logic as data. `defmixin` declares the injection point and the
  handler in one form: the target is checked against the real game classes
  at compile time (a typo fails this namespace's AOT with the list of
  overloads), and the build generates the mixin class, the mixins json and
  the fabric.mod.json entry — no Java stub, no handwritten json. The handler
  is a plain defn behind a var: redefine it from the REPL and the very next
  server tick runs the new body.

  This example counts server ticks. Delete it if your mod needs no mixins;
  for cases `defmixin` does not cover, see the Java escape hatch in
  src/main/java/mymod/mixin/."
  (:require [skein.mixin :refer [defmixin]]))

(def tick-count
  "Server tick counter, incremented from the generated HEAD inject into
  MinecraftServer.tickServer."
  (atom 0))

(defmixin server-tick
  {:target net.minecraft.server.MinecraftServer}
  (inject {:at :head}
    (tickServer [_server _have-time _ci]
      (swap! tick-count inc))))
