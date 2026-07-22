(ns skein.state
  "Where a mod's state lives: an atom of ordinary Clojure data that survives
  hot reload and, when asked to, the world save.

      (state/defstate scores
        {:schema [:map-of :uuid :int]
         :init {}
         :persist? true})

      (swap! scores update player-uuid (fnil inc 0))
      @scores                                  ;=> {#uuid\"...\" 3}

  `defstate` defines a var holding an atom. The atom itself is owned by this
  namespace's registry, keyed by the state's id — so re-evaluating the mod's
  namespace from the REPL hands back the *same* atom: the handlers around it
  reload, the data in it does not. That is the whole point: hot reload covers
  logic, and state is not logic.

  With `:persist? true` the value is written into the world save and read back
  when the world loads — one file per state, named after its id, holding the
  data encoded through its schema (see skein.codec). The lifecycle is bound to
  the *server*, not the JVM: the value loads when a server starts and returns to
  `:init` when it stops, so a client that leaves one world and joins another
  never carries the first world's data over.

  What persistence is and is not:
  - it is per world save, on the server side — a scoreboard, quest progress,
    the mod's own counters;
  - it is not per entity or per chunk — data that belongs to a specific entity,
    chunk or block entity is attached to it instead (see skein.attach), so it
    travels and unloads with its owner.

  A persisted state's id names its save file, so renaming the var (or its
  namespace) points the mod at a fresh, empty file. Pass an explicit `:id` for
  anything whose data must outlive a refactor.

  Threading: the value is an atom, so reads and swaps are safe from any thread,
  including a REPL eval thread. What is *not* safe from another thread is
  touching the game with the value you read — that still goes through
  `skein.interop/on-server`."
  (:require [malli.core :as m]
            [skein.codec :as codec]
            [skein.events :as events]
            [skein.fx :as fx]
            [skein.id :as id]
            [skein.interop :as interop]
            [skein.schema :as schema])
  (:import (com.mojang.datafixers.util Pair)
           (com.mojang.serialization Codec DataResult)
           (java.util.function Function Supplier)
           (net.minecraft.server MinecraftServer)
           (net.minecraft.util.datafix DataFixTypes)
           (net.minecraft.world.level.saveddata SavedData SavedDataType)
           (net.minecraft.world.level.storage SavedDataStorage)))

(defonce ^:private ^org.slf4j.Logger logger
  (org.slf4j.LoggerFactory/getLogger "skein"))

(def declaration
  "A `defstate` declaration: what the state holds, and whether it is written to
  the world save."
  [:map {:closed true}
   [:schema {:optional true} :any]
   [:init {:optional true} :any]
   [:persist? {:optional true} :boolean]
   [:id {:optional true} :qualified-keyword]
   [:datafix {:optional true} :keyword]])

(defonce ^:private states
  ;; id -> {:atom :schema :init :persist? :datafix :holder :storage}. A defonce
  ;; registry is what makes the atoms survive a namespace reload.
  (atom {}))

(defonce ^:private installed
  ;; The server lifecycle handlers are registered once, on the first persistent
  ;; state — a mod with no persistence never touches the event bus.
  (atom false))

;;; The value <-> SavedData bridge
;;;
;;; The game stores a SavedData object and asks a Codec to write it. The object
;;; a mod cares about is the atom's value, so the SavedData instance here is an
;;; empty marker whose only job is to carry the dirty flag: the codec encodes
;;; what the atom currently holds, and decoding resets the atom.

(defn- holder-codec
  ^Codec [^Codec data-codec state-atom ^SavedData holder]
  (reify Codec
    (encode [_ _holder ops prefix]
      (.encode data-codec @state-atom ops prefix))
    (decode [_ ops input]
      (.map ^DataResult (.decode data-codec ops input)
            (reify Function
              (apply [_ pair]
                (reset! state-atom (.getFirst ^Pair pair))
                (Pair. holder (.getSecond ^Pair pair))))))))

(defn- datafix-type
  "The DataFixTypes a save file is read under. The default is the one vanilla
  itself uses for free-form, mod-authored NBT (`/data` command storage): data
  written by the current version is never touched, and a world carried across
  game versions is walked as arbitrary data rather than as some vanilla
  structure. Override with `:datafix` when a state's shape mirrors a vanilla one."
  ^DataFixTypes [k]
  (let [n (-> (or k :saved-data-command-storage) name (.replace "-" "_") .toUpperCase)]
    (try
      (DataFixTypes/valueOf n)
      (catch IllegalArgumentException e
        (throw (ex-info (str "Unknown :datafix " (pr-str k) ". Use a DataFixTypes constant as a keyword, e.g."
                             " :saved-data-command-storage (the default) or :level.")
                        {:datafix k} e))))))

(defn- attach!
  "Binds one persistent state to a running server's save: reads the stored value
  into the atom (or seeds it with `:init` for a fresh world) and marks the save
  dirty on every later change."
  [^MinecraftServer server id {:keys [schema init datafix] :as spec}]
  (let [state-atom (:atom spec)
        holder (proxy [SavedData] [])
        supplier (reify Supplier
                   (get [_]
                     ;; A world with no file for this state yet: start from :init.
                     (reset! state-atom init)
                     holder))
        saved-type (SavedDataType. (id/id id) supplier
                                   (holder-codec (codec/of schema) state-atom holder)
                                   (datafix-type datafix))
        ^SavedDataStorage storage (.getDataStorage server)]
    (.computeIfAbsent storage saved-type)
    (add-watch state-atom ::persist (fn [_ _ old new] (when (not= old new) (.setDirty holder))))
    (swap! states update id merge {:holder holder :storage storage})))

(defn- detach!
  "Unbinds a state from a stopped server: the value goes back to `:init` so the
  next world starts clean."
  [id spec]
  (let [state-atom (:atom spec)]
    (remove-watch state-atom ::persist)
    (reset! state-atom (:init spec))
    (swap! states update id dissoc :holder :storage)))

(defn- persistent-states []
  (into {} (filter (comp :persist? val)) @states))

(defn- running-server
  "The server whose save the states live in: the dedicated server, or a client's
  integrated server when a world is open. nil when there is none (yet)."
  ^MinecraftServer []
  (let [instance (try (interop/game) (catch Throwable _ nil))]
    (cond
      (instance? MinecraftServer instance) instance
      ;; A client instance reaches its integrated server reflectively — naming
      ;; the client class here would break loading on a dedicated server, which
      ;; does not ship it.
      (some? instance) (try
                         (clojure.lang.Reflector/invokeInstanceMethod
                          instance "getSingleplayerServer" (object-array 0))
                         (catch Throwable _ nil)))))

(defn- load-all! [^MinecraftServer server]
  (doseq [[id spec] (persistent-states)]
    (attach! server id spec)))

(defn- unload-all! [_server]
  (doseq [[id spec] (persistent-states)]
    (detach! id spec)))

(defn- ensure-installed! []
  (when-not @installed
    (events/on! :server/started #'load-all!)
    (events/on! :server/stopped #'unload-all!)
    (reset! installed true)))

;;; Declaration

(defn derived-id
  "The id of a state declared without one: `<ns>/<name>`, which is a legal
  content id as long as both parts are. The error names the fix rather than
  letting an Identifier failure surface from deep inside the save code.
  `defstate` calls it; mod code passes `:id` instead."
  [ns-sym state-name]
  (let [candidate (keyword (str ns-sym) (str state-name))]
    (try
      (id/id candidate)
      candidate
      (catch Exception e
        (throw (ex-info (str "Cannot derive a state id from " candidate
                             " — a state id must be a valid content id (lower case, [a-z0-9_.-])."
                             " Pass an explicit :id, e.g. {:id :mymod/" (name state-name) " ...}.")
                        {:state state-name} e))))))

(defn define!
  "Declares a state and returns its atom (see the ns docstring). Idempotent per
  id: a second call with the same id returns the existing atom, keeping the data
  across a namespace reload, and adopts the new `:schema`/`:init` for later
  saves. `defstate` is the form to use in mod code — this is what it calls."
  [id {:keys [schema init persist?] :as decl}]
  (schema/validate! declaration decl "defstate declaration")
  (when (and persist? (not schema))
    (throw (ex-info (str "State " id " is :persist? true but has no :schema."
                         " Persisted data is stored through its schema — declare one,"
                         " e.g. {:schema [:map-of :uuid :int] :init {} :persist? true}.")
                    {:state id})))
  (when schema
    ;; Fail at declaration time, not at world save: derive the codec now so an
    ;; unstorable schema is an error in the entrypoint (or in the REPL eval that
    ;; declared it), with the schema named.
    (codec/of schema)
    (schema/validate! (m/schema schema) init (str "initial value of state " id)))
  (let [existing (get @states id)
        state-atom (or (:atom existing) (atom init))]
    (when (and existing (not= (:persist? existing) (boolean persist?)) (:holder existing))
      (.warn logger "[skein] state {} changed its :persist? while a world is open — it takes effect on the next world load"
             (str id)))
    (swap! states assoc id (merge (select-keys existing [:holder :storage])
                                  {:atom state-atom
                                   :schema schema
                                   :init init
                                   :persist? (boolean persist?)
                                   :datafix (:datafix decl)}))
    (when (and persist? (not *compile-files*))
      ;; A `defstate` is a top-level form, so it also runs while the mod is AOT
      ;; compiled: the atom is all the compiler needs — the save wiring belongs
      ;; to a running game.
      (ensure-installed!)
      ;; Declared from the REPL while a world is already open: bind it now
      ;; instead of waiting for the next server start. A state that is already
      ;; bound (a re-declaration) keeps the binding it has.
      (when-not (:holder existing)
        (when-some [server (running-server)]
          (attach! server id (get @states id)))))
    state-atom))

(defmacro defstate
  "Defines `name` as the atom of a mod state (see the ns docstring).

      (defstate scores {:schema [:map-of :uuid :int] :init {} :persist? true})

  Options: `:schema` (a Malli schema, required to persist), `:init` (the value
  before anything is loaded or stored), `:persist? true` to write it into the
  world save, `:id` to pin the save file name against a rename, and `:datafix`
  for the rare state that should be read as a vanilla structure.

  Re-evaluating the form keeps the current value — the atom is looked up by id,
  not created anew."
  [name decl]
  `(def ~name
     (let [decl# ~decl]
       (define! (or (:id decl#) (derived-id '~(ns-name *ns*) '~name))
                (dissoc decl# :id)))))

;;; Introspection and REPL helpers

(defn state
  "The atom of a declared state by id — for the REPL, and for code that only
  has the id at hand."
  [id]
  (or (:atom (get @states id))
      (throw (ex-info (str "No state declared for " id ". Declared: " (vec (sort (keys @states))))
                      {:state id}))))

(defn declared
  "What is declared, as data: id -> {:persist? :saved? :value}, where `:saved?`
  says whether the state is currently bound to an open world's save."
  []
  (into (sorted-map)
        (map (fn [[id spec]]
               [id {:persist? (:persist? spec)
                    :saved? (some? (:holder spec))
                    :value @(:atom spec)}]))
        @states))

(defn flush!
  "Writes every persistent state to disk now, and blocks until it is written.
  For the REPL and for tests — the game already saves on its own schedule and
  when the world closes."
  []
  (doseq [[_ {:keys [holder]}] (persistent-states)
          :when holder]
    (.setDirty ^SavedData holder))
  (when-some [^SavedDataStorage storage (some :storage (vals (persistent-states)))]
    (.saveAndJoin storage))
  nil)

;;; Effects as data (layer B) — so a pure handler can update state the same way
;;; it changes the world.

(defmethod fx/fx! :swap-state [_ctx [_ id f & args]]
  (apply swap! (state id) f args))
