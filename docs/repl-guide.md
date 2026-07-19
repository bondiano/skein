# The REPL guide

A live nREPL connection into a running game is the reason to write mods in
Clojure. This guide covers connecting an editor, the hot-reload workflow, running
code on the game thread, and adding libraries live.

For enabling a REPL on a real server, read the
[production REPL security page](security.md) first — this guide is about the dev
loop.

## Connecting

Every dev run (`./gradlew runClient` or `./gradlew runServer`) starts an nREPL
server on `127.0.0.1:7888`. Change the port with `skein { nreplPort = … }` in
`build.gradle.kts`. If the REPL fails to start for any reason, the game still
runs — the REPL is never allowed to take the game down.

Connect with anything that speaks nREPL:

- **CIDER** (Emacs) — `M-x cider-connect`, host `localhost`, port `7888`.
- **Calva** (VS Code) — *Connect to a running REPL* → *Generic* → `localhost:7888`.
- **Plain terminal**:

  ```sh
  clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.7.0"}}}' \
    -M -m nrepl.cmdline --connect --port 7888
  ```

## The hot-reload loop

Handlers are registered as `#'var`s, so redefining one changes the live game on
its next call — no re-registration, no restart. Pure handlers are ordinary data
functions, so you can exercise them without the game even doing anything:

```clojure
(in-ns 'mymod.core)

;; Call a pure handler with a plain map — pure data in, effects (as data) out:
(greet {:player :steve})
;; => [[:tell :steve [:aqua "Hello from " [:gold "My Mod"] "!"]]]

;; Redefine it — the next player join runs the new body.
(defn greet [{:keys [player]}]
  [[:tell player [:light_purple "Patched live from the REPL!"]]])
```

The same is true of command handlers and `defmixin` handlers. See
[Hot reload](hot-reload.md) for the full boundary — logic reloads, registry
content does not.

## Running code on the game thread

An nREPL evaluation runs on its own thread. Most game state must only be touched
from the game thread, so reading or mutating the world from the REPL has to hop
onto it. `skein.interop` (and `skein.repl`) give you three macros that dispatch a
body onto the game thread **synchronously** and return its value:

```clojure
(require '[skein.interop :as interop]
         '[skein.world :as world])

(interop/on-server
  (let [level (.overworld (interop/server))]        ; a ServerLevel
    (world/set-block! level [0 100 0] {:block :minecraft/gold_block})
    (world/block-at level [0 100 0])))
;; => {:block :minecraft/gold_block}
```

- `on-server` / `on-client` / `on-game` are the same dispatch; the names just
  read better in server-side, client-side, or neutral code. Each blocks until the
  body runs on the game thread and returns its result (with a timeout, so a
  frozen game surfaces as an error rather than a hang).
- `interop/server`, `interop/client`, `interop/game`, `interop/players`, and
  `interop/player` fetch the live game objects to work with inside the body.

Anything that only reads or computes — inspecting registered content, calling a
pure handler, deref-ing a var — does not need the game thread. Only touching live
game state does.

### Optional: eval-on-game-thread middleware

If you would rather have *every* form you evaluate run on the game thread without
wrapping each one in `on-server`, start the REPL with game-thread eval enabled:

- dev: `skein { … }` wiring, or `-Dskein.nrepl.game-thread-eval=true`;
- the opt-in middleware `skein.repl.middleware/wrap-game-thread` wraps each
  eval message's code in an `on-game` dispatch. Sessions and interrupts still
  behave normally; the forms just execute on the game thread.

This trades throughput (everything queues onto one thread) for never having to
think about which thread you are on. It is off by default.

## Adding a library live (dev only)

To try a library without restarting, pull it into the running session:

```clojure
(require '[skein.repl :as repl])

(repl/add-lib! 'org.clojure/data.json "2.5.0")
;; resolves from Maven Central / Clojars via tools.deps, adds the jars
;; (with transitives) to the current session's classloader, returns the paths.

(require '[clojure.data.json :as json])
(json/write-str {:hello "world"})
```

`add-lib!` is **dev-only** — it throws in production, where mods must be
self-contained and bundle their dependencies at build time. Anything you add
lives until the JVM restarts, so once you're happy, pin the dependency in
`build.gradle` and rebuild.

## Production REPL

The production REPL is strictly opt-in, loopback-only, and reached through an SSH
tunnel — never an open port. It gets its own page because the security model is
the thing to understand before you enable it: see
[Production REPL security](security.md).
