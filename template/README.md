# My Mod — a Clojure Fabric mod

A starter template for writing a [Fabric](https://fabricmc.net/) mod in
Clojure with [Skein](https://github.com/bondiano/skein) — REPL-driven modding, hot reload
of your logic in a running game, and content declared as data.

Targets **Minecraft 26.1+** (unobfuscated: Mojang's real names, non-remapping
Loom) on **JDK 25**.

## Get started

1. Clone or generate this template.
2. Rename `mymod` everywhere to your own mod id — the folder
   `src/main/clojure/mymod/`, the namespaces, the ids in `fabric.mod.json`,
   the `com.example` group in `gradle.properties`, and the `loom { mods { }`
   name and lang keys. Keep your Clojure namespace root equal to the mod id
   (Skein lints for it).
3. Set the Skein version: open `gradle/libs.versions.toml` and replace
   `skein = "SET-ME"` with the release you target.
4. Run it:

```sh
./gradlew runClient    # client
./gradlew runServer    # dedicated server
```

Both start an nREPL server on `127.0.0.1:7888` (change it with
`skein { nreplPort = … }` in `build.gradle.kts`).

Build the mod jar with `./gradlew build` — it lands in `build/libs/`.

## What the build does

`build.gradle.kts` applies Loom and the Skein plugin, and that is the whole
build. The Skein plugin:

- compiles `src/main/clojure` (AOT, before `jar` — mods ship classes);
- pins the Clojure version (you never spell it out);
- warns on reflection on hot paths (opt into an error with
  `skein { reflectionWarnings = "error" }`);
- checks your namespace root is the mod id;
- generates the class + json for every `defmixin` (see below);
- starts the dev nREPL in every run config.

## The code

- **`mymod.core`** — the `main` entrypoint. It registers a gem item as data
  with `skein.core/register!` and attaches a **pure** join handler with
  `skein.events/on-pure!`: the handler takes the event's data and returns a
  vector of effects, and it is registered as a `#'var`, so redefining it from
  the REPL changes the live game.
- **`mymod.mixin`** — a `defmixin` that counts server ticks. The injection
  point and the handler are one form; the build verifies the target against
  the real game classes and generates everything Fabric needs.

Both are minimal on purpose — replace them with your own content and
handlers. Skein's FP layer also gives you `skein.world` (read the world as
data, mutate with explicit `!` effects), `skein.command` (Brigadier commands
as data), `skein.text` (hiccup → chat components), `skein.item` /
`skein.block` / `skein.entity` / `skein.player` snapshots, and
`skein.schedule` tick timers.

## A REPL session

The dev run opens an nREPL server. Connect with anything that speaks nREPL:

- **CIDER** (Emacs): `M-x cider-connect`, host `localhost`, port `7888`.
- **Calva** (VS Code): *Connect to a running REPL* → *Generic* → `localhost:7888`.
- **Plain terminal**:

  ```sh
  clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.7.0"}}}' \
    -M -m nrepl.cmdline --connect --port 7888
  ```

Then redefine a handler and watch the running game change on the next event —
no restart, no re-registration, because the var is registered, not its value:

```clojure
(in-ns 'mymod.core)

;; Pure handlers are just data functions — call one with a plain map, no
;; game required:
(greet {:player :steve})
;; => [[:tell :steve [:aqua "Hello from " [:gold "My Mod"] "! " "..."]]]

;; Hot reload: redefine it, the very next player join runs the new body.
(defn greet [{:keys [player]}]
  [[:tell player [:light_purple "Patched live from the REPL!"]]])

;; The mixin handler is a var too — watch the counter, then patch it:
(require '[mymod.mixin :as mixin])
@mixin/tick-count
```

Touch the live world from the REPL — always on the game thread:

```clojure
(require '[skein.interop :as interop]
         '[skein.world :as world])
(interop/on-server
  (let [level (.overworld (interop/server))]
    (world/set-block! level [0 100 0] {:block :minecraft/gold_block})
    (world/block-at level [0 100 0])))
;; => {:block :minecraft/gold_block}
```

## What hot reload covers — and what it does not

**Logic hot-reloads.** Event handlers, mixin handlers and any fn you call
through a var can be redefined in a live game — dev *and* production (the
production REPL is strictly opt-in and loopback-only; see
`config/skein/nrepl.properties`).

**Registry content does not.** The game freezes its registries right after
startup — a Minecraft constraint. Re-evaluating an unchanged `register!` is a
safe no-op; adding a *new* item or block needs a restart.

## Mixins

Two ways to touch game internals:

- **`defmixin`** (preferred) — declarative, in Clojure. See `mymod.mixin`.
  The target is checked at compile time, the class/json/`fabric.mod.json`
  entry are generated, and the handler stays hot-reloadable behind a var.
  Starter coverage: `:inject`, `:modify-arg`, `:modify-return`, and
  `:field` targets in `@At`.
- **Hand-written Java mixins** (escape hatch) — for what the declarative form
  cannot express. See `src/main/java/mymod/mixin/package-info.java` for how to
  wire one up.

## Production REPL

Off by default in production. To enable it on a server, edit
`config/skein/nrepl.properties` (documented inline). It binds loopback only —
reach it through an SSH tunnel, never an open port.

## Layout

```
build.gradle.kts                         Loom + Skein plugin — the whole build
settings.gradle.kts                      repositories, JDK 25 toolchain
gradle/libs.versions.toml                MC / Fabric / Skein versions
config/skein/nrepl.properties            production nREPL config (opt-in)
src/main/clojure/mymod/core.clj          entrypoint, content, pure handlers
src/main/clojure/mymod/mixin.clj         defmixin declaration + handler
src/main/java/mymod/mixin/               hand-written Java mixin escape hatch
src/main/resources/fabric.mod.json       mod metadata + Clojure entrypoint
src/main/resources/assets/mymod/         models, lang, textures
```
