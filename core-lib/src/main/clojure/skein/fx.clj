(ns skein.fx
  "Effects-as-data — an open registry (the optional \"layer B\") on top of the
  imperative `!`-functions.

  An effect is a vector `[:kind & args]`; a handler runs it against a context.
  Handlers are multimethods keyed by the effect kind, so a mod adds one with a
  plain `defmethod` and every effect it emits flows through the same seam:

      (defmethod skein.fx/fx! :my-mod/spawn-ruby [world [_ pos]]
        (world/set-block! world pos :my-mod/ruby-block))

      (defn on-use [ev]                 ; a pure handler: data -> effects
        [[:my-mod/spawn-ruby (:pos ev)]])

  Each handler stays a *thin* delegation to the matching `!`-function — no game
  logic is duplicated here, the effect is just the data name for a mutation.

  The registry ships empty on purpose: a built-in effect lives next to the
  domain namespace that owns its `!`-function (the world effects register when
  `skein.world` loads, the reply effect when `skein.command` loads, ...), and a
  mod adds its own the same way. A pure handler returns a vector of effects;
  the interpreter runs them with `run-effects!` — inside `on-server`, or
  directly when already on the game thread (an event or command callback is).

  Hot reload: re-evaluating a `(defmethod fx! :kind ...)` replaces that effect's
  handler live — the next effect of that kind runs the new body, no restart. To
  retire an effect, `(remove-method skein.fx/fx! :kind)`. `known-effects` lists
  what is currently registered.")

(defn effect-kind
  "The dispatch key of an effect: the keyword head of a `[:kind & args]`
  vector. Throws with an explanation for anything that is not one."
  [effect]
  (if (and (sequential? effect) (keyword? (first effect)))
    (first effect)
    (throw (ex-info (str "An effect must be a vector like [:kind & args] with a keyword kind, got: "
                         (pr-str effect))
                    {:effect effect}))))

(defmulti fx!
  "Run one effect `[:kind & args]` against `ctx` — the world, server or command
  source the effect acts on. Dispatches on the effect kind. Register a handler
  with `(defmethod fx! :my-mod/thing [ctx [_ & args]] ...)`. Handlers mutate
  game state, so a call must happen on the game thread — `run-effects!` under
  `on-server`, or an event/command callback that already runs there."
  (fn [_ctx effect] (effect-kind effect)))

(defn known-effects
  "A sorted vector of the effect kinds that currently have a handler."
  []
  (vec (sort (remove #{:default} (keys (methods fx!))))))

(defmethod fx! :default [_ctx effect]
  (let [kind (effect-kind effect)]
    (throw (ex-info (str "No effect handler for " kind
                         ". Register one with (defmethod skein.fx/fx! " kind " [ctx effect] ...)"
                         ", or check the kind for a typo. Known effects: " (known-effects))
                    {:effect effect :kind kind :known (known-effects)}))))

(defn run-effects!
  "Run a sequence of effects in order against `ctx`. Call it on the game thread
  (wrap in `on-server` from the REPL; an event or command callback is already
  there). Returns nil."
  [ctx effects]
  (when-not (sequential? effects)
    (throw (ex-info (str "run-effects! expects a sequence of effects (a vector of [:kind & args]), got: "
                         (pr-str effects))
                    {:effects effects})))
  (doseq [effect effects]
    (fx! ctx effect))
  nil)
