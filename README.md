# Skein

*A skein of Yarn — Clojure on Fabric.*

A Fabric language adapter for Clojure: REPL-driven modding (including production
servers), hot reload of logic via var indirection, and data-driven content.

**Target:** MC 26.1+ (the first unobfuscated versions — no yarn/intermediary, no
remapping), JDK 25.

## Documentation

- [Getting started](docs/getting-started.md) — from an empty directory to a
  running, hot-reloading mod.
- [The REPL guide](docs/repl-guide.md) — connecting an editor, running code on
  the game thread, adding libraries live.
- [Hot reload](docs/hot-reload.md) — what changes live and what needs a restart.
- [Architecture](docs/architecture.md) — how the adapter boots Clojure inside
  Fabric.
- [Production REPL security](docs/security.md) — before you enable a REPL on a
  real server.

New to the project? Start with [getting started](docs/getting-started.md), or
read the [example mod](example-mod/README.md) for a complete mod that uses every
part of the toolkit.

## Modules

| Module | Purpose |
|---|---|
| `adapter/` | `ClojureLanguageAdapter` + Clojure runtime bootstrap |
| `runtime/` | classloader glue between Fabric's Knot and the Clojure runtime |
| `repl/` | nREPL lifecycle + middleware, dev and production |
| `gradle-plugin/` | plugin on top of the non-remapping Fabric Loom: AOT, reflection perf-lint |
| `core-lib/` | separate artifact: registry DSL, events, the FP layer, `defmixin` |
| `skein-scripts/` | separate mod: load `.clj` files at runtime, no build step |
| `example-mod/` | demo + integration test |
| `template/` | standalone repo template for modders (not part of the build) |

## Building

```sh
./gradlew build
```

The JDK 25 toolchain is provisioned automatically (foojay resolver). Dependency
versions live in [gradle/libs.versions.toml](gradle/libs.versions.toml).
