# Skein Scripts

Load plain Clojure (`.clj`) files from your server's config directory at
runtime — no build step, no AOT, edit and reload live. Skein Scripts is a
Fabric mod built on the [Skein](../adapter) Clojure adapter: it boots the
shared Clojure runtime and evaluates your scripts with full access to the game,
other mods, and (optionally) any library you declare in a `deps.edn`.

It is the convenience-first counterpart to a compiled Skein mod: you trade
startup cost and packaging for the ability to drop a file in a folder and see
it run.

## Layout

```
config/skein/
  scripts/            # your .clj files, loaded in sorted name order
    10-world.clj
    20-commands.clj
  scripts.properties  # optional settings (see below)
  deps.edn            # optional tools.deps map for script dependencies
  repo/               # local dependency cache (created on demand)
```

Scripts load in **sorted file-name order**, so a numeric prefix
(`10-…`, `20-…`) gives explicit ordering when one script depends on another.
Each file is evaluated in isolation: one that throws is logged with its full
stack trace and skipped — a single broken script never stops the others or
takes the server down.

A script with no `(ns …)` form runs in a fresh `user` namespace. Add an
`(ns my.script …)` form at the top if you want your own namespace.

## Reloading

Change a script, then reload without restarting the server:

- **`/skein reload`** — reloads every script (requires operator permission).
- **`/skein status`** — reports the last load result.
- **From a REPL** — call `(skein-scripts.core/reload!)`.
- **File watcher** — set `watch=true` and edits trigger a reload automatically.

Reloading re-evaluates the files, so redefining a function or an event handler
changes the running game immediately — the same hot-reload story as the rest of
Skein.

## Settings — `config/skein/scripts.properties`

All keys are optional:

| Key           | Default          | Meaning                                                        |
| ------------- | ---------------- | -------------------------------------------------------------- |
| `phase`       | `server-started` | When scripts first load: `mod-init`, `server-starting`, `server-started`. |
| `watch`       | `false`          | Reload automatically when a `.clj` file changes.               |
| `offline`     | `false`          | Resolve `deps.edn` from the local cache only, never the network. |
| `scripts-dir` | `config/skein/scripts` | Override the scripts directory.                          |
| `repo-dir`    | `config/skein/repo`    | Override the dependency cache directory.                 |

`mod-init` loads during mod initialization, before the world exists — use it
only for scripts that do not touch game state. The default, `server-started`,
runs once the server is fully up.

## Dependencies — `config/skein/deps.edn`

Declare libraries with a standard [tools.deps](https://clojure.org/guides/deps_and_cli)
map and `require` them from your scripts:

```clojure
{:deps {org.clojure/data.json {:mvn/version "2.5.0"}}}
```

They are downloaded once into `config/skein/repo/` and added to the scripts'
classloader. Pre-populate that cache and set `offline=true` to run without
network access. If you leave out `deps.edn`, nothing is resolved.

## What to expect

**Trust model — scripts are arbitrary code.** A file in `config/skein/scripts/`
runs with the full authority of the server process: the file system, the
network, the JVM. Treat scripts exactly like a mod jar you are about to
install. Only put scripts there that you wrote or fully trust, and keep the
directory writable only by people you trust with the server.

**Performance — not for hot paths.** Scripts are compiled when they load, which
is as fast as compiled Skein code once loaded. But this mod exists for
iteration speed, not for shaving microseconds. Reflective interop is fine for
glue and commands; if a script runs every tick and shows up in a profile, add
type hints (they work here without restriction — MC is unobfuscated) or move
that logic into a compiled mod.

**Registry content can't be added from scripts.** The game freezes its
registries during startup, before scripts load. Scripts can add commands,
event handlers, and any game logic, but they cannot register new blocks, items,
or other registry content — declare that in a compiled mod. Logic hot-reloads;
registry content does not.
