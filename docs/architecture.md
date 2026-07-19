# Architecture

How Skein runs Clojure as a first-class Fabric mod language: one shared runtime
per JVM, classloading that lets Clojure see the whole game, and var indirection
that makes hot reload free.

## The unobfuscated target

Skein targets **Minecraft 26.1+**, the first versions Mojang ships with real
class and method names. That single fact removes a whole layer that older Fabric
tooling was built around:

- no yarn or intermediary mappings, no `tiny` files, no refmaps;
- the Loom plugin is the **non-remapping** `net.fabricmc.fabric-loom` — there is
  no `remapJar` step and dev classes are byte-for-byte the production classes;
- reflective interop works the same in dev and in production, so a REPL can call
  any game method by its real name with no mapping indirection.

Type hints therefore matter only for performance on hot paths, never for
correctness.

## Modules

| Module | What it is | Ships to users as |
|---|---|---|
| `adapter` | The language adapter mod — registers `"clojure"` and boots the runtime | A Fabric library mod |
| `runtime` | Classloader glue between Fabric's Knot loader and the Clojure runtime | Bundled inside the adapter |
| `repl` | nREPL lifecycle and middleware (dev and production) | Bundled inside the adapter |
| `core-lib` | The Clojure API: registry DSL, events, the FP layer, `defmixin` | A Maven/Clojars artifact mods depend on |
| `gradle-plugin` | Build plugin on top of Loom: AOT, lints, version pinning, mixin codegen | A Gradle plugin |

The adapter, its `runtime`, and `repl` are one deliverable — the library mod you
install. `core-lib` is a separate artifact your mod compiles against, and the
Gradle plugin is what compiles it.

## Booting Clojure inside Fabric

### One runtime per JVM

The adapter registers a Fabric `LanguageAdapter` under the id `"clojure"`. A mod
opts into it with `"adapter": "clojure"` on its entrypoint in `fabric.mod.json`.

The Clojure runtime is **booted lazily, once per JVM**, on the first Clojure
entrypoint the loader resolves. Every Clojure mod in the game then shares that
one runtime — they see each other's namespaces and can call each other directly,
exactly like Clojure code in a single application. Booting the runtime is cheap
(on the order of ~100 ms and ~1400 classes), so there is no per-mod startup tax.

### Classloader glue

Fabric loads mods through its own **Knot** classloader, which is where the game
and every other mod live. Clojure, meanwhile, compiles and loads code through a
`DynamicClassLoader`. Skein parents that `DynamicClassLoader` on Knot.

The effect: Clojure code — compiled ahead of time, or typed into a live REPL —
resolves game classes, Fabric API classes, and other mods' classes with no
shading and no bridge layer. The runtime sees the real game classpath.

### Entrypoint resolution

The adapter resolves an entrypoint value to a Clojure var:

- `some.ns/the-var` names a var directly;
- `some.ns` alone resolves the conventional `init` var in that namespace (the
  loader does not hand the adapter the entrypoint *key*, so `init` is the
  convention).

For single-method (SAM) interfaces like `ModInitializer`, the adapter wraps the
target `IFn` in a `java.lang.reflect.Proxy`. For multi-method interfaces it
expects a `reify`-style object and checks it actually implements the interface.
Every resolution failure is fail-fast and names the mod, the entrypoint, and
what was missing — including the list of public vars in the namespace when a var
name doesn't resolve.

## Var indirection: why hot reload is free

The SAM proxy does one deliberately important thing: **it derefs the var on
every call** rather than capturing the function value once.

```
entrypoint  ──proxy──►  #'mymod.core/init  ──deref-per-call──►  current fn
```

Because the game holds the *proxy* (which points at the *var*), redefining the
var from a REPL means the very next call runs the new function body — no
re-registration, no restart. The API layer follows the same rule everywhere:
event handlers, command handlers, and `defmixin` handlers are all registered as
`#'var`s, not as function values. This var-per-call deref is the feature, not an
overhead to optimize away.

See [Hot reload](hot-reload.md) for what this covers and what it cannot (registry
content, which the game freezes after startup).

## AOT-only for compiled mods

Compiled Skein mods are **ahead-of-time compiled**. The Gradle plugin runs the
Clojure compiler before `jar`, so the mod ships classes and pays no runtime
compilation cost when the game starts. Runtime `.clj` loading is a separate,
convenience-first product ([Skein
Scripts](https://github.com/bondiano/skein/tree/main/skein-scripts)); a normal
mod does not compile at startup.

## Jar-in-Jar bundling

The adapter bundles its dependencies — the Clojure runtime, `spec.alpha`,
`core.specs.alpha`, and nREPL — with Fabric's Jar-in-Jar mechanism, wrapped so
the loader deduplicates them. Consequently **a mod must not bundle its own
`clojure.jar`**: the Gradle plugin fails the build if it tries, so every Clojure
mod in a game runs against the one runtime the adapter provides.

## The Gradle plugin's job

Applied on top of Loom, the plugin makes a Clojure mod build with an empty
`skein { }` block:

- adds the `src/main/clojure` source set and AOT-compiles it before `jar`;
- pins the Clojure/nREPL versions to exactly what the adapter bundles;
- runs a reflection perf-lint (`*warn-on-reflection*`) — a warning by default,
  an opt-in error via `skein { reflectionWarnings = "error" }`;
- lints that your namespace root matches the mod id;
- bans bundling adapter-provided jars;
- generates the class and metadata for every `defmixin`;
- starts the dev nREPL in every Loom run config.
