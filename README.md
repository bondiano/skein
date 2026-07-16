# Skein

*A skein of Yarn — Clojure on Fabric.*

A Fabric language adapter for Clojure: REPL-driven modding (including production
servers), hot reload of logic via var indirection, and data-driven content.

**Target:** MC 26.1+ (the first unobfuscated versions — no yarn/intermediary, no
remapping), JDK 25.

## Modules

| Module | Purpose |
|---|---|
| `adapter/` | `ClojureLanguageAdapter` + Clojure runtime bootstrap |
| `runtime/` | classloader glue between Fabric's Knot and the Clojure runtime |
| `repl/` | nREPL lifecycle + middleware, dev and production |
| `gradle-plugin/` | plugin on top of the non-remapping Fabric Loom: AOT, reflection perf-lint |
| `core-lib/` | separate artifact: registry DSL, events, helpers |
| `example-mod/` | demo + integration test |
| `template/` | standalone repo template for modders (not part of the build) |

## Building

```sh
./gradlew build
```

The JDK 25 toolchain is provisioned automatically (foojay resolver). Dependency
versions live in [gradle/libs.versions.toml](gradle/libs.versions.toml).
