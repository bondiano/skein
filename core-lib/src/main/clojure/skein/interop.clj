(ns skein.interop
  "Interop helpers for mod logic and REPL sessions.

  - `(game)` / `(client)` / `(server)` — the game instance from the loader;
  - `(player \"Name\")`, `(players)` — players on the server;
  - `(on-game ...)`, `(on-client ...)`, `(on-server ...)` — synchronous
    dispatch of the body onto the game thread (mandatory for mutating the
    world from the REPL: the game is single-threaded, touching its state
    from an eval thread is a race).

  Dispatch delegates to skein.repl (the module lives in the adapter) via
  requiring-resolve: a compile-time require would drag nREPL into the
  mod's AOT compilation."
  (:import (net.fabricmc.api EnvType)
           (net.fabricmc.loader.api FabricLoader)
           (net.minecraft.core RegistryAccess)
           (net.minecraft.server MinecraftServer)
           (net.minecraft.server.level ServerPlayer)))

(defn dev?
  "true in the dev environment (a Loom run), false on the production
  launcher."
  []
  (.isDevelopmentEnvironment (FabricLoader/getInstance)))

(defn client-env?
  "true when the physical side is a client (not a dedicated server)."
  []
  (= EnvType/CLIENT (.getEnvironmentType (FabricLoader/getInstance))))

(defn ensure-client!
  "Guards a client-only call: throws an actionable error when this JVM runs a
  dedicated server, which ships no client classes. `what` names the thing being
  attempted (a keybinding, a HUD overlay, ...). Client-side surfaces
  (skein.client.*) call this before touching a client class so the failure reads
  as \"move it to the client entrypoint\" rather than a NoClassDefFoundError."
  [what]
  (when-not (client-env?)
    (throw (ex-info (str what " is client-side, and this JVM runs a dedicated server."
                         " Register it from the mod's client entrypoint (fabric.mod.json"
                         " \"client\": [...]) — client surfaces only exist there.")
                    {:side :server}))))

(defn game
  "The game instance: MinecraftClient on the client, MinecraftServer on a
  dedicated server. Throws until the game exists (early init phases)."
  []
  (or (.getGameInstance (FabricLoader/getInstance))
      (throw (ex-info "No game instance yet — it exists only after startup; entrypoint init runs before the game object is created"
                      {}))))

(defn client
  "The Minecraft (client) instance. Errors on a dedicated server."
  []
  (when-not (client-env?)
    (throw (ex-info "(client) is client-side only — this JVM runs a dedicated server; use (server)" {})))
  (game))

(defn server
  "The MinecraftServer (dedicated) instance. Errors on the client: reach
  the singleplayer integrated server via
  (.getSingleplayerServer (client))."
  ^MinecraftServer []
  (when (client-env?)
    (throw (ex-info "(server) returns the dedicated server — this JVM is a client; use (.getSingleplayerServer (client)) for the integrated server"
                    {})))
  (game))

(defn players
  "A vector of the players online."
  ([] (players (server)))
  ([^MinecraftServer server]
   (vec (.getPlayers (.getPlayerList server)))))

(defn player
  "The ServerPlayer with the given name (nil when offline)."
  (^ServerPlayer [player-name] (player (server) player-name))
  (^ServerPlayer [^MinecraftServer server player-name]
   (.getPlayerByName (.getPlayerList server) player-name)))

(def ^:dynamic *registry-access*
  "A RegistryAccess for coercions that resolve dynamic-registry ids (enchantments
  and other datapack-loaded content, which are not in BuiltInRegistries). nil by
  default; `registry-access` falls back to the running server's. Bind it when a
  coercion must run without a reachable server (e.g. a client-side context with
  the integrated server's access)."
  nil)

(defn registry-access
  "The RegistryAccess to resolve dynamic-registry ids against: the bound
  `*registry-access*` if set, otherwise the running dedicated server's, otherwise
  nil (early startup, or a client with no integrated server yet). Callers that
  need it should raise an actionable error on nil."
  ^RegistryAccess []
  (or *registry-access*
      (try (.registryAccess (server)) (catch Exception _ nil))))

(defmacro on-game
  "Runs body on the game thread synchronously, returns body's value."
  [& body]
  `((requiring-resolve 'skein.repl/dispatch-sync) (fn [] ~@body)))

(defmacro on-client
  "= on-game; the name reads better in client-side code."
  [& body]
  `(on-game ~@body))

(defmacro on-server
  "= on-game; the name reads better in server-side code."
  [& body]
  `(on-game ~@body))
