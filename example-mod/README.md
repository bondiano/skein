# Skein Example Mod

A small but complete Fabric mod written in Clojure with
[Skein](../README.md). It demonstrates every piece of the toolkit and doubles
as the integration test in CI:

- **Entrypoint** — `skein-example.core/init`, declared in
  [`fabric.mod.json`](src/main/resources/fabric.mod.json) with
  `"adapter": "clojure"`;
- **Registry DSL** — a ruby block and a ruby item registered declaratively
  with `skein.core/register!` (the block gets its `BlockItem` and creative-tab
  entry automatically);
- **FP layer** — the event handlers are **pure functions** of the event's data
  that return a vector of effects (`skein.events/on-pure!`), the messages are
  `skein.text` hiccup, and the world is read through the pure `skein.world`
  query. Data at the boundaries, effects at the edge;
- **Commands as data** — `/ruby give` and `/ruby about` are a `skein.command`
  declaration whose `:run` handlers return effects, exactly like the events;
- **Vars, not values** — every handler is registered as a `#'var`, so it
  hot-reloads from the REPL;
- **Mixin as data** — `defmixin` in
  [`skein-example.mixin`](src/main/clojure/skein_example/mixin.clj) declares
  the injection point *and* the handler in one form. The target is verified
  against the real game classes at compile time, the mixin class is generated
  by the build, and the config is registered in `fabric.mod.json`
  automatically — no Java stub, no handwritten mixins json. The handler is a
  plain fn behind a var, hot-reloadable like everything else.

## Running

```sh
./gradlew :example-mod:runServer   # dedicated server, nREPL on port 7899
./gradlew :example-mod:runClient   # client, same nREPL setup
```

The Skein Gradle plugin starts an nREPL server in every dev run
(`skein { nreplPort = 7899 }` in this project's `build.gradle.kts`; the
default is 7888).

## A REPL session

Connect with anything that speaks nREPL — CIDER, Calva, or plain
`clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.7.0"}}}' -M -m nrepl.cmdline --connect --port 7899`.

```clojure
;; What did the mod register?
(require '[skein.core :as skein])
(skein/registered)
;; => {:blocks [:skein_example/ruby_block], :items [:skein_example/ruby :skein_example/ruby_block]}

;; Which event handlers are attached? (:pure marks a style-B handler.)
(require '[skein.events :as events])
(events/handlers)
;; => [[:block/use #'skein-example.core/on-ruby-use :pure]
;;     [:player/join #'skein-example.core/greet :pure]]

;; The handlers are pure data functions — call one with a plain map, no
;; game required:
(in-ns 'skein-example.core)
(greet {:player :steve})
;; => [[:tell :steve [:aqua "Welcome to Skein! " [:gold "..."] " ..."]]]

;; Hot reload: redefine a handler — the very next event runs the new body.
;; No re-registration, no restart; the var is registered, not its value.
(defn greet [{:keys [player]}]
  [[:tell player [:light_purple "Patched live from the REPL!"]]])

;; The mixin ticks on the real server thread; its logic is the same kind
;; of var — watch it, then redefine it:
(require '[skein-example.mixin :as mixin])
@mixin/tick-count
;; => 20347
(defn my-tick [server have-time ci] (swap! mixin/tick-count + 2))
(alter-var-root #'mixin/server-mixin-tickServer (constantly my-tick))

;; Commands are data too — /ruby give and /ruby about are declared with
;; skein.command; their :run handlers hot-reload the same way.
(require '[skein.command :as command])
(command/defined)
;; => [:ruby]

;; Touch the live world from the REPL — always on the game thread. The
;; world is read as data (block-at) and mutated with an explicit effect
;; (set-block!):
(require '[skein.interop :as interop]
         '[skein.world :as world])
(interop/on-server
  (let [level (.overworld (interop/server))]      ; a ServerLevel
    (world/set-block! level [0 100 0] {:block :minecraft/gold_block})
    (world/block-at level [0 100 0])))
;; => {:block :minecraft/gold_block}
```

## What hot reload covers — and what it does not

**Logic hot-reloads.** Event handlers, mixin handlers and any fn you call
through a var can be redefined in a live game — dev *and* production (the
production REPL is strictly opt-in and loopback-only).

**Registry content does not.** The game freezes its registries right after
startup; that is a Minecraft constraint, not a Skein one. Re-evaluating a
namespace whose `register!` declarations are unchanged is a safe no-op; a
changed declaration logs a warning and keeps the old content; registering a
*new* block/item needs a game restart, and `register!` says so in its error
message.

## Layout

```
src/main/clojure/skein_example/core.clj    entrypoint, content, pure event handlers, command
src/main/clojure/skein_example/mixin.clj   defmixin declaration + handler (hot-reloadable)
src/main/resources/assets/skein_example/   blockstate, models, lang, textures
src/test/                                  headless integration tests (clojure.test in a fabric-loader-junit env)
src/prodSmoke/                             production smoke test (clojure.test): real fabric-server-launch + nREPL
```

## Tests

```sh
./gradlew :example-mod:test           # headless: loader, content, dev REPL
./gradlew :example-mod:l2ServerTest   # boots a headless dedicated server; FP domain layer (L2/L3)
```

`test` is the lightweight fabric-loader-junit suite (mod discovery, registry
DSL, event hot reload, the dev nREPL). `l2ServerTest` boots a real dedicated
server in-process (a flat world in a scratch dir, server side) and drives the
FP domain layer — `world` block/entity effects, `item` stack round-trips, and
the `/ruby` command dispatched through the live Brigadier tree — on the real
server thread. The production topology is covered separately by
`prodReplSmokeTest` (opt-in, needs network).
