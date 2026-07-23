# Hot reload — what it covers

Skein hot-reloads **logic** in a running game. It does **not** hot-reload
**registry content**, because the game freezes its registries after startup. This
page explains the line and why it falls where it does.

## The one-sentence version

> Redefine a function and the live game runs the new code on the next call.
> Add a new block or item and you need to restart.

## Why logic reloads: var indirection

Skein never hands the game a *function value*. It hands the game something that
holds a **var** and derefs it on every call:

```
game / event / command / mixin  ──►  #'mymod.core/handler  ──deref-each-call──►  current fn
```

When you `(defn handler …)` again from the REPL, the var now points at the new
function, and the next call the game makes runs it. Nothing was captured, so
nothing is stale. This is why the workflow is just "re-evaluate the form."

Everything you attach through the API layer follows this rule:

- **Entrypoints** — the adapter wraps them in a proxy over the var.
- **Event handlers** — `skein.events/on!` and `on-pure!` register a `#'var`.
- **Command handlers** — a `skein.command` declaration's `:run` is a var.
- **Mixin handlers** — a `defmixin` body is compiled to a var the generated
  mixin calls.
- **Scheduled timers** — `skein.schedule` callbacks are vars too.
- **Packet handlers** — `skein.net/on!` stores the var, and the receiver the
  game holds reads it on every packet.

So all of your behavior — how the mod reacts, computes, and mutates — is live.

## Why content does not reload: frozen registries

Minecraft **freezes its registries** shortly after startup: once the game is
running, the set of registered blocks, items, entity types, and so on is sealed.
That is a Minecraft constraint, not a Skein choice, and it is what keeps the
game's ids stable across the client/server boundary.

`skein.core/register!` collects your declarations and flushes them in the correct
startup phase, *before* the freeze. Once the game is up:

- **Re-evaluating an unchanged `register!` is a safe no-op** — it recognizes the
  declaration and does nothing.
- **A changed declaration logs a warning and keeps the old content** — the block
  is already frozen; the code can't retroactively change it.
- **Registering a brand-new id after the freeze fails fast** — `register!`
  returns an error that says, in plain words, that adding content needs a game
  restart. (The frozen registry itself would also throw from a block/item
  constructor, so this is caught early with a useful message rather than a raw
  exception.)

## Why your data is not lost: state lives outside the namespace

Reloading a namespace re-evaluates its top-level forms, so a plain
`(def counters (atom {}))` would throw the old map away every time you reload —
the classic way to lose a session's worth of play while iterating.

`skein.state/defstate` avoids that: the atom belongs to a registry keyed by the
state's id, and re-evaluating the form looks it up instead of creating a new one.

```clojure
(state/defstate scores
  {:id :mymod/scores :schema [:map-of :uuid :int] :init {} :persist? true})
```

Reload the namespace as often as you like — the handlers around `scores` become
the new code, the numbers inside it stay. With `:persist? true` the value is also
written into the world save, so it survives a server restart, and data that
belongs to one entity, chunk or block entity is attached to that thing instead
(`skein.attach`), where the game saves and unloads it with its owner.

The line here is the same one as everywhere else: **logic reloads, data
persists.** An attachment's *declaration* is content-like — its shape is fixed
for the run, and changing it needs a restart — but the attached values, like
state, are untouched by a reload.

## What this means day to day

| You change… | Reloads live? |
|---|---|
| An event / command / mixin handler body | ✅ yes — next call |
| A helper fn any handler calls | ✅ yes — next call |
| A scheduled-timer callback | ✅ yes — next tick it fires |
| The message/text a handler produces | ✅ yes (it's just handler logic) |
| The value in a `defstate` atom or an attachment | ✅ kept — a reload does not touch it |
| What a packet handler does with a payload | ✅ yes — next packet |
| The shape (`:schema`) of an attachment or a packet | ❌ no — restart |
| Adding a new block, item, or other registry entry | ❌ no — restart |
| The properties of an already-registered block | ❌ no — restart |

The practical pattern: **register your content once, iterate on your logic
forever.** Structure a mod so the registry declarations are small and stable, and
put everything you actually tune — behavior, balancing, messages, commands —
behind handlers you can redefine from the REPL.

## Dev and production

Logic hot reload works in **both** dev and production. In production it goes
through the opt-in, loopback-only REPL — see
[Production REPL security](security.md). The registry boundary is identical in
both: content is frozen after startup either way.

See also: [the REPL guide](repl-guide.md) for the connect-and-redefine loop, and
[architecture](architecture.md) for the var-indirection mechanism in more detail.
