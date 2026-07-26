(ns skein.client.keys
  "Keybindings as data: a binding is declared once, its handler is a var.

      ;; from the client entrypoint
      (keys/define! :mymod/open {:name \"key.mymod.open\" :key \"key.keyboard.g\"})
      (keys/on! :mymod/open #'on-open)

      (defn on-open [{:keys [client]}] ...)

  A declaration names the binding (`:name`, a translation key shown in the
  Controls screen), its default key (`:key`, e.g. \"key.keyboard.g\" or
  \"key.mouse.4\"; omit for an unbound binding the player assigns) and an
  optional `:category` (a keyword for a vanilla group like :misc, or a
  namespaced id string to register your own).

  The handler is a var, so redefining it from the REPL changes what the key does
  the next time it is pressed. Skein polls every declared binding once per client
  tick and, for each press since the last tick, dereferences the current handler
  var and calls it with `{:key <id> :client <Minecraft> :side :client}`. As
  elsewhere, `on!` takes a side-effecting handler and `on-pure!` a pure one that
  returns a vector of effects (run through skein.fx against the client).

  Re-declaring a binding is a no-op: a KeyMapping is registered with the game
  once at startup (like registry content), so its default key and category are
  fixed for the run; only the handler var hot-reloads. During AOT compilation
  define! is a no-op returning :skein/aot-compiling.

  Client-only: declare from the mod's client entrypoint. Needs fabric-api (the
  key-mapping module) on the classpath."
  (:require [skein.fx :as fx]
            [skein.id :as id]
            [skein.interop :as interop]
            [skein.schema :as schema])
  (:import (com.mojang.blaze3d.platform InputConstants)
           (net.minecraft.client KeyMapping KeyMapping$Category Minecraft)))

(defonce ^:private ^org.slf4j.Logger logger
  (org.slf4j.LoggerFactory/getLogger "skein"))

(def declaration
  "A keybinding declaration: its display name, default key and category."
  [:map {:closed true}
   [:name :string]
   [:key {:optional true} :string]
   [:category {:optional true} [:or :string :keyword]]])

(defonce ^:private bindings
  ;; id -> {:decl :mapping KeyMapping :handler var :style :fn|:pure}. A defonce
  ;; registry: the KeyMapping is registered with the game once, a namespace
  ;; reload only swaps the handler var inside it.
  (atom {}))

;; Whether the one per-tick poll listener is installed yet. Installed lazily on
;; the first define!, so a mod that declares no bindings pays nothing.
(defonce ^:private polling? (atom false))

;;; Coercion of the declaration's data to game objects

(def ^:private vanilla-categories
  {:movement    (fn [] KeyMapping$Category/MOVEMENT)
   :misc        (fn [] KeyMapping$Category/MISC)
   :multiplayer (fn [] KeyMapping$Category/MULTIPLAYER)
   :gameplay    (fn [] KeyMapping$Category/GAMEPLAY)
   :inventory   (fn [] KeyMapping$Category/INVENTORY)
   :creative    (fn [] KeyMapping$Category/CREATIVE)
   :spectator   (fn [] KeyMapping$Category/SPECTATOR)
   :debug       (fn [] KeyMapping$Category/DEBUG)})

(defn- ->category
  "The KeyMapping category: a keyword names a vanilla group; a string or
  namespaced keyword registers (or reuses) a custom category by id."
  ^KeyMapping$Category [category]
  (cond
    (nil? category) (KeyMapping$Category/MISC)
    (and (keyword? category) (not (namespace category)))
    (if-some [f (vanilla-categories category)]
      (f)
      (throw (ex-info (str "Unknown keybinding category " category ". Use one of "
                           (vec (sort (keys vanilla-categories)))
                           ", or a namespaced id (:mymod/keys or \"mymod:keys\") for your own.")
                      {:category category})))
    :else (KeyMapping$Category/register (id/id category))))

(defn- ->key
  "The InputConstants key for a \"key.keyboard.g\"-style name, or UNKNOWN
  (an unbound binding) when none was given."
  ^com.mojang.blaze3d.platform.InputConstants$Key [^String key-name]
  (if key-name
    (try
      (InputConstants/getKey key-name)
      (catch Exception e
        (throw (ex-info (str "Unknown key name " (pr-str key-name)
                             ". Expected a token like \"key.keyboard.g\" or \"key.mouse.4\".")
                        {:key key-name} e))))
    InputConstants/UNKNOWN))

(defn- build-mapping
  ^KeyMapping [{:keys [name key category]}]
  (let [k (->key key)]
    (KeyMapping. name (.getType k) (.getValue k) (->category category))))

;;; The per-tick poll — installed once, reads the registry every tick

(defn- dispatch!
  "Runs the handler currently registered for a pressed binding — looked up on
  every press, which is what makes it hot-reloadable."
  [id ^Minecraft client]
  (when-some [{:keys [handler style]} (get @bindings id)]
    (let [data {:key id :client client :side :client}]
      (if (= :pure style)
        (when-some [effects (handler data)]
          (fx/run-effects! client effects))
        (handler data)))))

(defn- poll!
  "Once per client tick: for each binding, drain its presses since the last tick
  and dispatch each one."
  [^Minecraft client]
  (doseq [[id {:keys [^KeyMapping mapping]}] @bindings]
    (while (.consumeClick mapping)
      (dispatch! id client))))

(defn- ensure-polling! []
  (when (compare-and-set! polling? false true)
    (.register net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents/END_CLIENT_TICK
               (reify net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndTick
                 (onEndTick [_ client] (poll! client))))))

;;; Declaration

(defn- ensure-fabric-api! [id]
  (try
    (Class/forName "net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper"
                   false (.getClassLoader clojure.lang.RT))
    (catch ClassNotFoundException e
      (throw (ex-info (str "Cannot declare the keybinding " id
                           ": skein.client.keys wraps the Fabric key-mapping API, but fabric-api is not"
                           " on the classpath. Add it to the mod's dependencies.")
                      {:binding id} e)))))

(defn define!
  "Declares a keybinding and returns its id (see the ns docstring). `id` is a
  namespace-qualified keyword. Idempotent per id: re-evaluating the declaring
  namespace keeps the registered mapping; a *changed* declaration logs a warning
  and keeps the old one, because the mapping is registered with the game at
  startup. During AOT compilation the call is a no-op returning
  :skein/aot-compiling."
  [id decl]
  (schema/validate! declaration decl "keybinding declaration")
  (when-not (and (keyword? id) (namespace id))
    (throw (ex-info (str "A keybinding id must be a namespace-qualified keyword like :mymod/open, got: "
                         (pr-str id))
                    {:binding id})))
  (if *compile-files*
    :skein/aot-compiling
    (do
      (interop/ensure-client! (str "The keybinding " id))
      (ensure-fabric-api! id)
      (if-some [existing (get @bindings id)]
        (do
          (when (not= (:decl existing) decl)
            (.warn logger "[skein] keybinding {} is already declared; its key and category are fixed for this run — restart the game to apply the changed declaration"
                   (str id)))
          id)
        (let [mapping (build-mapping decl)]
          (net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper/registerKeyMapping mapping)
          (swap! bindings assoc id {:decl decl :mapping mapping :handler nil :style :fn})
          (ensure-polling!)
          id)))))

(defn- binding-of [id]
  (or (get @bindings id)
      (throw (ex-info (str "No keybinding declared for " id
                           ". Declare it from the client entrypoint with (keys/define! " id " {...})."
                           " Declared: " (vec (sort (keys @bindings))))
                      {:binding id}))))

(defn- register-handler! [id handler-var style]
  (binding-of id)
  (when-not (var? handler-var)
    (throw (ex-info (str "The handler for " id " must be a var (#'my-handler), got: " (pr-str handler-var)
                         ". Registering the var — not the fn — is what makes the handler hot-reloadable.")
                    {:binding id :handler handler-var})))
  (swap! bindings update id assoc :handler handler-var :style style)
  id)

(defn on!
  "Registers a var as the binding's handler: a function of the press data map
  `{:key <id> :client <Minecraft> :side :client}`. Registering again replaces
  the handler; the poll dereferences the current var on every press, so
  redefining the handler takes effect immediately."
  [id handler-var]
  (register-handler! id handler-var :fn))

(defn on-pure!
  "Registers a *pure* handler var: a function of the press data map that returns
  a vector of effects (see skein.fx), run against the client. A nil return runs
  nothing."
  [id handler-var]
  (register-handler! id handler-var :pure))

;;; Introspection

(defn mapping
  "The game's KeyMapping for a declared binding — for the Fabric/vanilla APIs
  that take one (`isDown`, reading the currently bound key)."
  ^KeyMapping [id]
  (:mapping (binding-of id)))

(defn declared
  "What is declared, as data: id -> {:name ... :key ... :handler var} (for the
  REPL)."
  []
  (into (sorted-map)
        (map (fn [[id {:keys [decl handler]}]] [id (assoc decl :handler handler)]))
        @bindings))
