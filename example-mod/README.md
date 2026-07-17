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
- **Events** — handlers attached as **vars** with `skein.events/on!`, so they
  hot-reload from the REPL;
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

;; Which event handlers are attached?
(require '[skein.events :as events])
(events/handlers)
;; => [[:block/use #'skein-example.core/on-ruby-use]
;;     [:player/join #'skein-example.core/greet]]

;; Hot reload: redefine a handler — the very next event uses the new fn.
;; No re-registration, no restart; the var is registered, not its value.
(in-ns 'skein-example.core)
(defn greet [player]
  (.sendSystemMessage player (net.minecraft.network.chat.Component/literal
                              "Patched live from the REPL!")))

;; The mixin ticks on the real server thread; its logic is the same kind
;; of var — watch it, then redefine it:
(require '[skein-example.mixin :as mixin])
@mixin/tick-count
;; => 20347
(defn my-tick [server have-time ci] (swap! mixin/tick-count + 2))
(alter-var-root #'mixin/server-mixin-tickServer (constantly my-tick))

;; Touch the live world from the REPL — always on the game thread:
(require '[skein.interop :as interop])
(interop/on-server
  (mapv #(.getString (.getName %)) (interop/players)))
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
src/main/clojure/skein_example/core.clj    entrypoint, content, event handlers
src/main/clojure/skein_example/mixin.clj   defmixin declaration + handler (hot-reloadable)
src/main/resources/assets/skein_example/   blockstate, models, lang, textures
src/test/                                  headless integration tests (clojure.test in a fabric-loader-junit env)
src/prodSmoke/                             production smoke test (clojure.test): real fabric-server-launch + nREPL
```
