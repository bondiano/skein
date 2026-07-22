# Getting started

This walks you from an empty directory to a Clojure Fabric mod that runs, hot
reloads from a REPL, and builds to a jar.

If you'd rather read finished code first, the
[example mod](https://github.com/bondiano/skein/tree/main/example-mod) is a
small but complete mod that uses every part of the toolkit.

## Prerequisites

- **JDK 25** — the toolchain Minecraft 26.x needs. You do not have to install it
  by hand: the build provisions it automatically through the foojay resolver, so
  any recent JDK is enough to *start* Gradle.
- **A Clojure editor with an nREPL client** — [CIDER](https://cider.mx/) (Emacs),
  [Calva](https://calva.io/) (VS Code), or the plain `clj` command line all work.
  Nothing Skein-specific is required; if it speaks nREPL, it connects.

You do **not** need yarn mappings, an intermediary, or a remapping step.
Minecraft 26.1+ ships Mojang's real names, so dev and production class names are
identical and there is nothing to remap.

## 1. Start from the template

The [template](https://github.com/bondiano/skein/tree/main/template) is a
buildable skeleton — a `main` entrypoint, a pure event handler, a `defmixin`,
and the production REPL config, all wired up. Copy it into a new directory (or
use it as a GitHub template repository).

## 2. Rename `mymod` to your mod id

Pick a mod id (lowercase, `a–z0–9_-`) and replace `mymod` everywhere:

- the source folder `src/main/clojure/mymod/`;
- the namespace roots inside those files (`mymod.core`, `mymod.mixin`);
- the `id` and entrypoint in `src/main/resources/fabric.mod.json`;
- the `com.example` group in `gradle.properties`;
- the `loom { mods { } }` name and language-adapter keys in `build.gradle.kts`.

**Keep your namespace root equal to the mod id.** Skein lints for it — a mod id
of `coolmod` expects namespaces under `coolmod.*` — because that is how the game
maps an entrypoint back to your code.

## 3. Set the Skein version

Open `gradle/libs.versions.toml` and replace the placeholder:

```toml
skein = "SET-ME"   # -> the Skein release you target
```

Everything else — the Minecraft version, the Fabric Loader version, the Clojure
version — flows from that one line and the plugin. You never spell out the
Clojure version yourself; the plugin pins the exact one the adapter bundles.

## 4. Run it

```sh
./gradlew runClient    # the game client
./gradlew runServer    # a dedicated server
```

Either one starts your mod **and** a dev nREPL server on `127.0.0.1:7888`
(change the port with `skein { nreplPort = … }` in `build.gradle.kts`). The
first run downloads Minecraft and the JDK toolchain, so it takes a while; later
runs are fast.

## 5. Connect a REPL and change the running game

With the game still running, connect your editor to `localhost:7888` (see the
[REPL guide](repl-guide.md) for CIDER/Calva/`clj` specifics), then redefine a
handler:

```clojure
(in-ns 'mymod.core)

;; A pure handler is an ordinary data function — call it with a plain map,
;; no game required:
(greet {:player :steve})
;; => [[:tell :steve [:aqua "Hello from " [:gold "My Mod"] "!"]]]

;; Redefine it. The very next player join runs the new body — no restart,
;; no re-registration, because the handler is registered as a #'var.
(defn greet [{:keys [player]}]
  [[:tell player [:light_purple "Patched live from the REPL!"]]])
```

That loop — edit, re-eval, watch the live game change — is the whole point of
Skein. See [Hot reload](hot-reload.md) for exactly what it does and does not
cover.

## 6. Keep something between sessions

Sooner or later the mod needs to remember something. Declare it once and it both
survives your REPL reloads and goes into the world save:

```clojure
(require '[skein.state :as state])

(state/defstate scores
  {:id :mymod/scores
   :schema [:map-of :uuid :int]      ; also how it is written to disk
   :init {}
   :persist? true})

(swap! scores update player-uuid (fnil inc 0))
```

`scores` is a plain atom of plain data. Reload the namespace as often as you
like — the value stays; the world save keeps it across restarts. Data that
belongs to one entity, chunk or block entity goes on that thing instead, with
`skein.attach`, so the game saves and unloads it with its owner.

## 7. Build a jar

```sh
./gradlew build
```

The mod jar lands in `build/libs/`. It is a normal Fabric mod: drop it in a
server's or client's `mods/` folder alongside the Skein adapter. Because the
build AOT-compiles your Clojure to classes, there is no runtime compilation cost
at game startup.

## Where to go next

- [The REPL guide](repl-guide.md) — connecting editors, running code on the game
  thread, and adding libraries live in dev.
- [Hot reload](hot-reload.md) — what changes live and what needs a restart.
- [Architecture](architecture.md) — how the adapter boots Clojure inside Fabric.
- [Production REPL security](security.md) — before you ever enable a REPL on a
  real server.
