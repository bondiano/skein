(ns skein.command
  "Brigadier commands described as data.

      (command/def! :heal
        {:args [[:target :entity]]
         :permission :gamemasters
         :run (fn [{:keys [target source]}]
                [[:heal target] [:reply [:green \"healed\"]]])})

  A command is a declaration map:
  - :args       ordered argument specs, all required, appended after the command
                literal. A spec is `[name type]` or `[name type opts]`, where
                opts is a map — currently `{:suggests ...}` for tab-completion:
                a collection of strings/keywords (a fixed list), or a var/fn
                `{:source src :remaining partial} -> coll` (a var stays
                hot-reloadable — it is dereferenced on each completion). Types
                below;
  - :run        the handler — a function of the argument map (each arg under its
                keyword name, plus :source, the command source). It returns a
                vector of effects (run through skein.fx against the source), an
                integer Brigadier result, or nil (success);
  - :permission who may run it — a level keyword (:all :moderators :gamemasters
                :admins :owners), a command level 0-4, or a `(fn [source] bool)`
                predicate;
  - :subs       a map of sub-command-name -> declaration, for a command tree
                (a node has either :args or :subs, not both).

  Argument types: :int :double :bool :string :word :greedy, and the entity
  selectors :entity :entities :player :players.

  Registration is declarative and hot-reload-aware: `def!` collects the
  declaration and a single Fabric callback builds every collected command into
  the dispatcher when the server (re)loads commands. Re-`def!`ing a command
  changes its :run immediately (the tree looks the handler up on each run);
  changing its *structure* — args, subs, permission — applies on the next
  command reload (the game rebuilds the tree then), the same way registry
  content needs a restart.

  The :reply effect ships here: `[:reply msg]` sends `msg` (a skein.text form)
  back to the command source. Other effects a :run emits are the mod's own
  skein.fx handlers."
  (:require [skein.fx :as fx]
            [skein.text :as text])
  (:import (com.mojang.brigadier Command CommandDispatcher)
           (com.mojang.brigadier.arguments BoolArgumentType DoubleArgumentType
                                           IntegerArgumentType StringArgumentType)
           (com.mojang.brigadier.context CommandContext)
           (com.mojang.brigadier.suggestion SuggestionProvider)
           (net.minecraft.commands Commands CommandSourceStack SharedSuggestionProvider)
           (net.minecraft.commands.arguments EntityArgument)
           (net.minecraft.server.permissions Permission$HasCommandLevel PermissionLevel)))

;;; Argument types: the data keyword, the Brigadier ArgumentType that backs it,
;;; and how to read the parsed value out of a command context by name. The
;;; entity selector reads may throw CommandSyntaxException (no match); Brigadier
;;; turns that into the vanilla error shown to the player.

(def ^:private arg-types
  {:int      {:make #(IntegerArgumentType/integer)     :get (fn [ctx n] (IntegerArgumentType/getInteger ctx n))}
   :double   {:make #(DoubleArgumentType/doubleArg)    :get (fn [ctx n] (DoubleArgumentType/getDouble ctx n))}
   :bool     {:make #(BoolArgumentType/bool)           :get (fn [ctx n] (BoolArgumentType/getBool ctx n))}
   :string   {:make #(StringArgumentType/string)       :get (fn [ctx n] (StringArgumentType/getString ctx n))}
   :word     {:make #(StringArgumentType/word)         :get (fn [ctx n] (StringArgumentType/getString ctx n))}
   :greedy   {:make #(StringArgumentType/greedyString) :get (fn [ctx n] (StringArgumentType/getString ctx n))}
   :entity   {:make #(EntityArgument/entity)           :get (fn [ctx n] (EntityArgument/getEntity ctx n))}
   :entities {:make #(EntityArgument/entities)         :get (fn [ctx n] (EntityArgument/getEntities ctx n))}
   :player   {:make #(EntityArgument/player)           :get (fn [ctx n] (EntityArgument/getPlayer ctx n))}
   :players  {:make #(EntityArgument/players)          :get (fn [ctx n] (EntityArgument/getPlayers ctx n))}})

(def ^:private arg-type-names (set (keys arg-types)))

(def ^:private permission-levels
  #{:all :moderators :gamemasters :admins :owners})

;;; Declaration validation and normalization — pure, no game types, unit-tested
;;; without booting the game. The result is a node tree the builder walks; the
;;; only non-data value it carries is the :run fn.

(defn- normalize-permission [command-name perm]
  (cond
    (fn? perm) {:pred perm}
    (keyword? perm)
    (do (when-not (contains? permission-levels perm)
          (throw (ex-info (str ":permission " (pr-str perm) " for " command-name
                               " is not a level keyword. Supported: " (vec (sort permission-levels))
                               ", a command level 0-4, or a (fn [source] boolean).")
                          {:command command-name :permission perm})))
        {:level perm})
    (integer? perm)
    (do (when-not (<= 0 perm 4)
          (throw (ex-info (str ":permission " perm " for " command-name
                               " must be a command level 0-4.")
                          {:command command-name :permission perm})))
        {:level-id perm})
    :else (throw (ex-info (str ":permission for " command-name " must be a level keyword ("
                               (vec (sort permission-levels)) "), a command level 0-4, or a"
                               " (fn [source] boolean), got: " (pr-str perm))
                          {:command command-name :permission perm}))))

(defn- normalize-arg [command-name arg]
  (when-not (and (vector? arg) (<= 2 (count arg) 3))
    (throw (ex-info (str "Each :args entry of " command-name " must be a [name type] or"
                         " [name type opts] vector, got: " (pr-str arg))
                    {:command command-name :arg arg})))
  (let [[nm type opts] arg]
    (when-not (or (keyword? nm) (string? nm))
      (throw (ex-info (str "Argument name for " command-name " must be a keyword or string, got: " (pr-str nm))
                      {:command command-name :arg arg})))
    (when-not (contains? arg-type-names type)
      (throw (ex-info (str "Unknown argument type " (pr-str type) " for " command-name
                           ". Supported: " (vec (sort arg-type-names)))
                      {:command command-name :arg arg :supported (vec (sort arg-type-names))})))
    (when (and (some? opts) (not (map? opts)))
      (throw (ex-info (str "Argument options for " command-name " must be a map like {:suggests ...}, got: " (pr-str opts))
                      {:command command-name :arg arg})))
    (let [suggests (:suggests opts)]
      (when (and (some? suggests) (not (or (coll? suggests) (var? suggests) (ifn? suggests))))
        (throw (ex-info (str ":suggests for " command-name " must be a collection of strings, or a var/fn"
                             " returning one, got: " (pr-str suggests))
                        {:command command-name :arg arg})))
      (cond-> {:name (name nm) :type type}
        (some? suggests) (assoc :suggests suggests)))))

(declare normalize-node)

(defn- normalize-sub [command-name path [sub-name sub-spec]]
  (when-not (or (keyword? sub-name) (string? sub-name))
    (throw (ex-info (str "Sub-command name under " command-name " must be a keyword or string, got: " (pr-str sub-name))
                    {:command command-name :sub sub-name})))
  (normalize-node command-name (conj path (name sub-name)) sub-spec))

(def ^:private node-keys #{:args :permission :run :subs})

(defn- normalize-node [command-name path spec]
  (when-not (map? spec)
    (throw (ex-info (str "Command declaration for " command-name " at " path " must be a map, got: " (pr-str spec))
                    {:command command-name :path path :declaration spec})))
  (when-some [unknown (seq (remove node-keys (keys spec)))]
    (throw (ex-info (str "Unknown command keys " (vec unknown) " for " command-name " at " path
                         ". Supported: " (vec (sort node-keys)))
                    {:command command-name :path path :unknown (vec unknown)})))
  (let [{:keys [args run subs]} spec]
    (when (and run (not (ifn? run)))
      (throw (ex-info (str ":run for " command-name " at " path " must be a function, got: " (pr-str run))
                      {:command command-name :path path :run run})))
    (when (and (seq subs) (not (map? subs)))
      (throw (ex-info (str ":subs for " command-name " at " path " must be a map of name -> declaration, got: " (pr-str subs))
                      {:command command-name :path path :subs subs})))
    (when (and (seq args) (seq subs))
      (throw (ex-info (str command-name " at " path " has both :args and :subs — a command node has one or the other."
                           " Put the args on the sub-commands instead.")
                      {:command command-name :path path})))
    (when-not (or run (seq subs))
      (throw (ex-info (str command-name " at " path " needs a :run handler or a :subs tree.")
                      {:command command-name :path path})))
    {:literal (last path)
     :path path
     :args (mapv #(normalize-arg command-name %) args)
     :permission (when (contains? spec :permission)
                   (normalize-permission command-name (:permission spec)))
     :run? (boolean run)
     :run run
     :subs (mapv #(normalize-sub command-name path %) subs)}))

(defn- collect-leaves
  "Every runnable node keyed by its literal path -> {:run fn :args arg-specs}.
  The dispatcher looks a handler up here on each run, so re-def! hot-reloads it."
  [node]
  (reduce (fn [m sub] (merge m (collect-leaves sub)))
          (if (:run? node) {(:path node) {:run (:run node) :args (:args node)}} {})
          (:subs node)))

;;; Registry: the collected commands (by name, for building the tree) and the
;;; runnable leaves (by path, for hot-reloadable dispatch).

(defonce ^:private registry (atom {:commands {} :leaves {} :listener false}))

;;; Execution — reached only in-game, on the server thread.

(defn- extract-args [arg-specs ^CommandContext ctx source]
  (reduce (fn [m {:keys [name type]}]
            (assoc m (keyword name) ((:get (arg-types type)) ctx name)))
          {:source source}
          arg-specs))

(defn- run-result! [source result]
  (cond
    (nil? result) Command/SINGLE_SUCCESS
    (integer? result) (int result)
    (sequential? result) (do (fx/run-effects! source result) Command/SINGLE_SUCCESS)
    :else (throw (ex-info (str ":run must return a vector of effects, an integer, or nil, got: " (pr-str result))
                          {:result result}))))

(defn- invoke-run [path ^CommandContext ctx]
  (if-some [leaf (get-in @registry [:leaves path])]
    (let [source (.getSource ctx)]
      (run-result! source ((:run leaf) (extract-args (:args leaf) ctx source))))
    (throw (ex-info (str "No command handler registered at " path) {:path path}))))

;;; Building the Brigadier tree — in-game only, at command-registration time.

(defn- permission-predicate [perm]
  (let [pred (if-some [p (:pred perm)]
               p
               (let [level (if-some [k (:level perm)]
                             (PermissionLevel/valueOf (.toUpperCase ^String (name k)))
                             (PermissionLevel/byId (int (:level-id perm))))
                     permission (Permission$HasCommandLevel. level)]
                 (fn [^CommandSourceStack src] (.hasPermission (.permissions src) permission))))]
    (reify java.util.function.Predicate
      (test [_ src] (boolean (pred src))))))

(defn- executes-command [path]
  (reify Command
    (run [_ ctx] (invoke-run path ctx))))

(defn- as-string [s] (if (keyword? s) (name s) (str s)))

(defn- suggestion-provider
  "A Brigadier SuggestionProvider from a :suggests value: a fixed collection of
  strings/keywords, or a var/fn called with `{:source :remaining}` returning
  one. A var is dereferenced per request (the completions stay hot-reloadable)."
  ^SuggestionProvider [suggests]
  (reify SuggestionProvider
    (getSuggestions [_ ctx builder]
      (let [values (if (coll? suggests)
                     suggests
                     (suggests {:source (.getSource ^CommandContext ctx)
                                :remaining (.getRemaining builder)}))]
        (SharedSuggestionProvider/suggest ^Iterable (map as-string values) builder)))))

(defn- build-args
  "A chain of required-argument builders for the (non-empty) arg specs; the last
  one carries the executes handler for the path. An arg's :suggests attaches a
  tab-completion provider."
  [args path]
  (let [[a & more] args
        builder (Commands/argument (:name a) ((:make (arg-types (:type a)))))]
    (when-some [suggests (:suggests a)]
      (.suggests builder (suggestion-provider suggests)))
    (if (seq more)
      (.then builder (build-args more path))
      (.executes builder (executes-command path)))
    builder))

(defn- build-node [node]
  (let [literal (Commands/literal (:literal node))]
    (when-some [perm (:permission node)]
      (.requires literal (permission-predicate perm)))
    (when (and (:run? node) (empty? (:args node)))
      (.executes literal (executes-command (:path node))))
    (when (seq (:args node))
      (.then literal (build-args (:args node) (:path node))))
    (doseq [sub (:subs node)]
      (.then literal (build-node sub)))
    literal))

(defn- register-all! [^CommandDispatcher dispatcher]
  (doseq [node (vals (:commands @registry))]
    (.register dispatcher (build-node node))))

(defn- ensure-fabric-command-api! []
  (try
    (Class/forName "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback"
                   false (.getClassLoader clojure.lang.RT))
    (catch ClassNotFoundException e
      (throw (ex-info (str "skein.command needs the Fabric command API (fabric-command-api-v2) on the classpath."
                           " Add fabric-api to the mod's dependencies.")
                      {} e)))))

(defn- ensure-listener!
  "Registers, once, the Fabric callback that builds every collected command into
  the dispatcher whenever the server (re)loads commands."
  []
  (when-not (:listener @registry)
    (ensure-fabric-command-api!)
    (.register
     ^net.fabricmc.fabric.api.event.Event
     net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback/EVENT
     (reify net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
       (register [_ dispatcher _build-ctx _selection]
         (register-all! dispatcher))))
    (swap! registry assoc :listener true)))

;;; The built-in :reply effect — meaningful when the effect context is a command
;;; source (as it is for effects returned from a command :run).

(defmethod fx/fx! :reply [^CommandSourceStack source [_ msg]]
  (.sendSuccess source
                (reify java.util.function.Supplier
                  (get [_] (text/text msg)))
                false)
  nil)

;;; Public API

(defn def!
  "Registers a command from a declaration (see the ns docstring). Returns the
  command name. A no-op during AOT compilation (*compile-files*) — the game is
  not bootstrapped in the compiling JVM."
  [command-name spec]
  (when-not (or (keyword? command-name) (string? command-name))
    (throw (ex-info (str "Command name must be a keyword or string, got: " (pr-str command-name))
                    {:command command-name})))
  (let [node (normalize-node command-name [(name command-name)] spec)]
    (when-not *compile-files*
      (swap! registry (fn [r]
                        (-> r
                            (assoc-in [:commands command-name] node)
                            (update :leaves merge (collect-leaves node)))))
      (ensure-listener!))
    command-name))

(defn defined
  "A sorted vector of the command names currently declared (for the REPL)."
  []
  (vec (sort-by str (keys (:commands @registry)))))
