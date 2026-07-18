(ns skein-scripts.command
  "Registers the `/skein` command via the Fabric command API:

  - `/skein reload` — reloads all scripts (ops only, permission level 2);
  - `/skein status` — reports the last load result.

  The command runs on the server thread, so `reload!` can touch game state
  directly. The Fabric/Mojang classes are referenced by their real names
  (MC 26.x is unobfuscated); they are on the mod's compile classpath via
  fabric-api and Loom."
  (:require [clojure.string :as str])
  (:import (com.mojang.brigadier Command)
           (java.util.function Supplier)
           (net.minecraft.commands CommandSourceStack Commands)
           (net.minecraft.network.chat Component)))

(defn- feedback!
  "Sends a non-broadcast success message to the command source."
  [^CommandSourceStack source ^String message]
  (.sendSuccess source
                (reify Supplier (get [_] (Component/literal message)))
                false))

(defn- summarize [status]
  (let [scripts (:scripts status)
        failed (filter #(= :error (:status %)) scripts)]
    (if (:loaded-at status)
      (str "Skein Scripts: " (count scripts) " loaded"
           (when (seq failed)
             (str ", " (count failed) " failed — "
                  (str/join ", " (map :name failed))))
           " (at " (:loaded-at status) ")")
      "Skein Scripts: nothing loaded yet")))

(defn- run-reload [reload!]
  (reify Command
    (run [_ ctx]
      (let [^CommandSourceStack source (.getSource ctx)]
        (try
          (let [result (reload!)]
            (feedback! source (summarize result)))
          (catch Throwable t
            (feedback! source (str "Skein Scripts reload failed: " (.getMessage t)))))
        1))))

(defn- run-status [status]
  (reify Command
    (run [_ ctx]
      (feedback! (.getSource ctx) (summarize (status)))
      1)))

(defn build-tree
  "Builds and registers the `/skein` command tree on the brigadier
  dispatcher. `reload!` returns the new status map; `status` returns the
  current one."
  [^com.mojang.brigadier.CommandDispatcher dispatcher reload! status]
  (.register dispatcher
             ;; Operator gate: level 2 (gamemasters) — the conventional bar
             ;; for a server-management command. hasPermission returns a
             ;; Predicate<CommandSourceStack>, exactly what .requires wants.
             (-> (Commands/literal "skein")
                 (.requires (Commands/hasPermission Commands/LEVEL_GAMEMASTERS))
                 (.then (-> (Commands/literal "reload")
                            (.executes (run-reload reload!))))
                 (.then (-> (Commands/literal "status")
                            (.executes (run-status status)))))))

(defn register!
  "Registers the `/skein` command with Fabric. `reload!`/`status` as in
  build-tree. Resolved lazily so a missing fabric-api fails here with a
  clear message rather than at ns load."
  [reload! status]
  (.register ^net.fabricmc.fabric.api.event.Event net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback/EVENT
             (reify net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
               (register [_ dispatcher _registry-access _environment]
                 (build-tree dispatcher reload! status)))))
