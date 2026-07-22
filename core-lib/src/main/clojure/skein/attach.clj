(ns skein.attach
  "Data attached to a thing in the world — an entity, a block entity, a chunk or
  a level — that lives and dies with it.

      (attach/define! :mymod/mana {:schema :int :init 0
                                   :persist? true :copy-on-death? true})

      (attach/attached player :mymod/mana)          ;=> 0
      (attach/update! player :mymod/mana + 5)       ;=> 5
      (attach/detach! player :mymod/mana)

  Where `skein.state` holds what belongs to the *world save* as a whole, this
  holds what belongs to one *thing*: mana on a player, a cooldown on a block
  entity, a claim on a chunk. The game moves it with its owner — saved with the
  entity, unloaded with the chunk, gone when the owner is gone — and with
  `:persist? true` writes it into the owner's own NBT through the state's schema
  (see skein.codec).

  Values are ordinary Clojure data and are replaced, never mutated in place:
  `update!` reads, applies the function and writes the new value back.

  Declare attachments from the init entrypoint, next to `register!`: like
  registry content, an attachment type is registered once per game start and
  cannot be re-declared with a different shape while the game runs — a re-eval
  of the declaring namespace is a no-op (with a warning if the declaration
  changed), the values themselves are untouched.

  Needs fabric-api (the data-attachment module); the classes are resolved on
  first use, so a mod without it fails at `define!` with an explanation rather
  than at namespace load. Syncing an attachment to the client is a networking
  concern and arrives with that layer."
  (:require [malli.core :as m]
            [skein.codec :as codec]
            [skein.coerce :as coerce]
            [skein.fx :as fx]
            [skein.id :as id]
            [skein.schema :as schema])
  (:import (java.util.function Supplier)
           (net.minecraft.core BlockPos)
           (net.minecraft.server.level ServerLevel)))

(defonce ^:private ^org.slf4j.Logger logger
  (org.slf4j.LoggerFactory/getLogger "skein"))

(def declaration
  "An attachment declaration: what the attached value looks like, and how it
  behaves when its owner is saved or respawns."
  [:map {:closed true}
   [:schema {:optional true} :any]
   [:init {:optional true} :any]
   [:persist? {:optional true} :boolean]
   [:copy-on-death? {:optional true} :boolean]])

(defonce ^:private attachments
  ;; id -> {:type AttachmentType :decl <declaration>}. Registered once per game
  ;; start: the registry is defonce so a namespace reload does not re-register.
  (atom {}))

(defn- ensure-fabric-api! [id]
  (try
    (Class/forName "net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry"
                   false (.getClassLoader clojure.lang.RT))
    (catch ClassNotFoundException e
      (throw (ex-info (str "Cannot declare the attachment " id
                           ": skein.attach wraps the Fabric data attachment API,"
                           " but fabric-api is not on the classpath. Add it to the mod's dependencies.")
                      {:attachment id} e)))))

(defn define!
  "Declares an attachment type and returns its id (see the ns docstring).

  Options: `:schema` (a Malli schema — required to persist), `:init` (the value
  read back before anything was attached), `:persist? true` to save it with its
  owner, `:copy-on-death? true` to carry it over when a player respawns.

  Idempotent per id: re-evaluating the declaring namespace keeps the registered
  type. A *changed* declaration logs a warning and keeps the old one — like
  registry content, the shape of an attachment is fixed for the run. During AOT
  compilation the call is a no-op returning :skein/aot-compiling: there is no
  attachment registry in the compiling JVM."
  [id {:keys [schema init persist? copy-on-death?] :as decl}]
  (schema/validate! declaration decl "attachment declaration")
  (when-not (and (keyword? id) (namespace id))
    (throw (ex-info (str "An attachment id must be a namespace-qualified keyword like :mymod/mana, got: "
                         (pr-str id))
                    {:attachment id})))
  (when (and persist? (not schema))
    (throw (ex-info (str "Attachment " id " is :persist? true but has no :schema."
                         " Persisted data is stored through its schema — declare one,"
                         " e.g. {:schema :int :init 0 :persist? true}.")
                    {:attachment id})))
  (when (and schema (some? init))
    (schema/validate! (m/schema schema) init (str "initial value of attachment " id)))
  ;; A declaration placed at the top level also runs while the mod is AOT
  ;; compiled — there is no attachment registry in the compiling JVM.
  (if *compile-files*
    :skein/aot-compiling
    (do
      (ensure-fabric-api! id)
      (if-some [existing (get @attachments id)]
        (do
          (when (not= (:decl existing) decl)
            (.warn logger "[skein] attachment {} is already declared; its shape is fixed for this run — restart the game to apply the changed declaration"
                   (str id)))
          id)
        (let [builder (try
                        (net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry/builder)
                        (catch LinkageError e
                          ;; The attachment registry comes up with the game — a
                          ;; standalone REPL or a plain unit-test JVM has the
                          ;; classes but not the loader that initializes them.
                          (throw (ex-info (str "Cannot declare the attachment " id
                                               " here: the Fabric attachment registry only exists inside a running game."
                                               " Declare attachments from the mod's init entrypoint, and try them out"
                                               " through the game's REPL.")
                                          {:attachment id} e))))
              builder (cond-> builder
                        persist? (.persistent (codec/of schema))
                        (some? init) (.initializer (reify Supplier (get [_] init)))
                        copy-on-death? (.copyOnDeath))
              attachment-type (.buildAndRegister
                               ^net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry$Builder builder
                               (id/id id))]
          (swap! attachments assoc id {:type attachment-type :decl decl})
          id)))))

(defn- attachment-type
  ^net.fabricmc.fabric.api.attachment.v1.AttachmentType [id]
  (or (:type (get @attachments id))
      (throw (ex-info (str "No attachment declared for " id
                           ". Declare it from the init entrypoint with (attach/define! " id " {...})."
                           " Declared: " (vec (sort (keys @attachments))))
                      {:attachment id}))))

(defn- ->target
  "The live thing an attachment hangs on. Accepts a snapshot (the live object
  rides in its meta) as readily as the object itself."
  ^net.fabricmc.fabric.api.attachment.v1.AttachmentTarget [x]
  (let [obj (if (map? x) (:skein/obj (meta x)) x)]
    (if (instance? net.fabricmc.fabric.api.attachment.v1.AttachmentTarget obj)
      obj
      (throw (ex-info (str "Cannot attach data to " (pr-str x)
                           " — an attachment target is an entity, a block entity, a chunk or a level."
                           (when (map? x)
                             " This snapshot carries no live object; read it from the world first."))
                      {:target x})))))

(defn attached
  "The value attached to `target` under `id` — its `:init` when nothing has been
  attached yet, nil when the attachment was declared without one. Reading never
  writes: an untouched target stays untouched (and unsaved)."
  [target id]
  (let [t (attachment-type id)
        init (:init (:decl (get @attachments id)))]
    (if (some? init)
      (.getAttachedOrElse (->target target) t init)
      (.getAttached (->target target) t))))

(defn attach!
  "Attaches `value` to `target` under `id`, replacing whatever was there.
  Returns the previous value."
  [target id value]
  (let [t (attachment-type id)]
    (when-some [s (:schema (:decl (get @attachments id)))]
      (schema/validate! (m/schema s) value (str "attached value for " id)))
    (.setAttached (->target target) t value)))

(defn update!
  "Applies `f` to the value attached under `id` (its `:init` when there is none
  yet) and attaches the result. Returns the new value."
  [target id f & args]
  (let [next-value (apply f (attached target id) args)]
    (attach! target id next-value)
    next-value))

(defn detach!
  "Removes the attachment under `id` from `target`; returns the value it had."
  [target id]
  (.removeAttached (->target target) (attachment-type id)))

(defn chunk-at
  "The chunk containing `pos` in `world`, as an attachment target — chunk data
  is attached to this."
  [^ServerLevel world pos]
  (.getChunkAt world ^BlockPos (coerce/->blockpos pos)))

(defn declared
  "What is declared, as data: id -> its declaration (for the REPL)."
  []
  (into (sorted-map) (map (fn [[id spec]] [id (:decl spec)])) @attachments))

;;; Effects as data (layer B) — thin delegation to the `!`-functions above.

(defmethod fx/fx! :attach [_ctx [_ target id value]] (attach! target id value))
(defmethod fx/fx! :detach [_ctx [_ target id]] (detach! target id))
(defmethod fx/fx! :update-attached [_ctx [_ target id f & args]] (apply update! target id f args))
