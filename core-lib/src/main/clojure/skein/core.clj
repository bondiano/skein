(ns skein.core
  "Skein core-lib: the declarative registry DSL.

  Content is described as data and registered in one call:

      (register!
       {:blocks {:mymod/ruby-block {:strength [3.0 6.0] :requires-tool true}}
        :items  {:mymod/ruby {:group :ingredients}}})

  Semantics:
  - a call from the init entrypoint (or from a top-level ns form — it runs
    when the entrypoint loads) lands in the phase where the registries are
    still open, registers the content immediately and records the
    declarations;
  - a repeated call with the same declaration (re-eval of the ns from the
    REPL) is a no-op;
  - a changed declaration for an already-registered id logs a warning:
    the game freezes its registries after startup, so hot reload covers
    logic (fns, event handlers) but not registry content;
  - a new id after the freeze is an error with an explanation (restart
    the game);
  - during AOT compilation (*compile-files*) the call is a no-op: the
    game classes are not bootstrapped in the compiling JVM, there is
    nothing to register into.

  A block gets its BlockItem registered automatically (disable with
  `:item? false`). `:group` puts the item into a creative tab (requires
  fabric-api; vanilla tabs are `:ingredients`, `:building_blocks`, ...
  — the default namespace is minecraft)."
  (:import (net.minecraft.core Registry)
           (net.minecraft.core.registries BuiltInRegistries Registries)
           (net.minecraft.resources Identifier ResourceKey)
           (net.minecraft.world.item BlockItem Item Item$Properties Rarity)
           (net.minecraft.world.level.block Block)
           (net.minecraft.world.level.block.state BlockBehaviour$Properties)))

(defonce ^:private ^org.slf4j.Logger logger
  (org.slf4j.LoggerFactory/getLogger "skein"))

(defonce ^:private state
  ;; :declarations [kind id] -> declaration, :registered [kind id] -> game
  ;; object, :groups tab -> [item-id ...], :group-listeners #{tab}.
  (atom {:declarations {} :registered {} :groups {} :group-listeners #{}}))

;;; Identifiers

(defn- ->identifier
  ^Identifier [id]
  (when-not (and (keyword? id) (namespace id))
    (throw (ex-info (str "Content id must be a namespace-qualified keyword like :mymod/ruby, got: " (pr-str id))
                    {:id id})))
  (try
    (Identifier/fromNamespaceAndPath (namespace id) (name id))
    (catch Exception e
      (throw (ex-info (str "Invalid content id " id ": " (.getMessage e)) {:id id} e)))))

(defn- tab->identifier
  ^Identifier [group]
  (if (namespace group)
    (Identifier/fromNamespaceAndPath (namespace group) (name group))
    (Identifier/fromNamespaceAndPath "minecraft" (name group))))

;;; Declaration validation

(def ^:private block-keys #{:strength :hardness :resistance :light-level :friction
                            :no-collision :requires-tool :instabreak :item? :group})
(def ^:private item-keys #{:stacks-to :durability :fire-resistant :rarity :group})

(defn- validate-keys! [id decl allowed what]
  (when-not (map? decl)
    (throw (ex-info (str what " declaration for " id " must be a map, got: " (pr-str decl))
                    {:id id :declaration decl})))
  (when-some [unknown (seq (remove allowed (keys decl)))]
    (throw (ex-info (str "Unknown " what " keys " (vec unknown) " for " id
                         ". Supported: " (vec (sort allowed)))
                    {:id id :unknown (vec unknown) :supported (vec (sort allowed))}))))

;;; Properties from data

(defn- block-properties
  ^BlockBehaviour$Properties [id decl]
  (let [p (BlockBehaviour$Properties/of)]
    (when-some [s (:strength decl)]
      (if (vector? s)
        (.strength p (float (s 0)) (float (s 1)))
        (.strength p (float s))))
    (when-some [h (:hardness decl)] (.destroyTime p (float h)))
    (when-some [r (:resistance decl)] (.explosionResistance p (float r)))
    (when-some [f (:friction decl)] (.friction p (float f)))
    (when-some [level (:light-level decl)]
      (.lightLevel p (reify java.util.function.ToIntFunction
                       (applyAsInt [_ _] (int level)))))
    (when (:no-collision decl) (.noCollision p))
    (when (:requires-tool decl) (.requiresCorrectToolForDrops p))
    (when (:instabreak decl) (.instabreak p))
    (.setId p (ResourceKey/create Registries/BLOCK (->identifier id)))
    p))

(defn- item-properties
  ^Item$Properties [id decl]
  (let [p (Item$Properties.)]
    (when-some [n (:stacks-to decl)] (.stacksTo p (int n)))
    (when-some [d (:durability decl)] (.durability p (int d)))
    (when (:fire-resistant decl) (.fireResistant p))
    (when-some [r (:rarity decl)]
      (.rarity p (Rarity/valueOf (.toUpperCase (name r)))))
    (.setId p (ResourceKey/create Registries/ITEM (->identifier id)))
    p))

;;; Creative tabs (require fabric-api; resolved reflectively so that
;;; register! without :group also works in a mod without fabric-api)

(defn- modify-output-listener
  "A dynamic Proxy instead of reify: the interface class is loaded only
  when :group is actually used."
  [^Class modify-output-class group]
  (java.lang.reflect.Proxy/newProxyInstance
   (.getClassLoader modify-output-class)
   (into-array Class [modify-output-class])
   (reify java.lang.reflect.InvocationHandler
     (invoke [_ proxy method args]
       (case (.getName ^java.lang.reflect.Method method)
         "modifyOutput"
         (let [output (aget ^objects args 0)]
           (doseq [item-id (get-in @state [:groups group])]
             (when-some [item (get-in @state [:registered [:items item-id]])]
               (clojure.lang.Reflector/invokeInstanceMethod
                output "accept" (object-array [item]))))
           nil)
         "toString" (str "skein group listener " group)
         "hashCode" (int (System/identityHashCode proxy))
         "equals" (identical? proxy (aget ^objects args 0))
         nil)))))

(defn- ensure-tab-listener!
  "Attaches a single listener per tab; on every fire it reads the current
  item list from the state — repeated register! calls only append."
  [group]
  (when-not (contains? (:group-listeners @state) group)
    (let [^Class events-class (try
                                (Class/forName "net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents")
                                (catch ClassNotFoundException e
                                  (throw (ex-info (str ":group " group " needs fabric-api (the creative tab API) on the classpath."
                                                       " Add fabric-api to the mod's dependencies, or drop the :group key.")
                                                  {:group group} e))))
          ^Class modify-output-class (Class/forName "net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents$ModifyOutput")
          ^Class event-class (Class/forName "net.fabricmc.fabric.api.event.Event")
          tab-key (ResourceKey/create Registries/CREATIVE_MODE_TAB (tab->identifier group))
          event (-> events-class
                    (.getMethod "modifyOutputEvent" (into-array Class [ResourceKey]))
                    (.invoke nil (object-array [tab-key])))]
      (-> event-class
          (.getMethod "register" (into-array Class [Object]))
          (.invoke event (object-array [(modify-output-listener modify-output-class group)])))
      (swap! state update :group-listeners conj group))))

(defn- add-to-group! [group item-id]
  (ensure-tab-listener! group)
  (swap! state update-in [:groups group]
         (fnil #(if (some #{item-id} %) % (conj % item-id)) [])))

;;; Registration

(defn- frozen-message [id]
  (str "Cannot register " id " — the game has frozen its registries."
       " Hot reload covers logic (fns, event handlers), not registry content;"
       " restart the game to add or change blocks/items."))

(defn- register-entry! [^Registry registry ^ResourceKey resource-key value]
  (Registry/register registry resource-key value))

(defn- flush-entry!
  "Registers a single declaration if it is new; skips a repeated one,
  and skips a changed one with a warning (the game froze the registry)."
  [kind id decl do-register!]
  (let [entry-key [kind id]
        previous (get-in @state [:declarations entry-key] ::none)]
    (cond
      (= previous decl) nil

      (not= previous ::none)
      (.warn logger "[skein] {} {} is already registered; registry content cannot be hot-reloaded — restart the game to apply the changed declaration"
             (str kind) (str id))

      :else
      ;; A frozen registry signals IllegalStateException both from
      ;; Registry.register and already from the Block/Item constructors
      ;; (intrusive holders) — translate both into an actionable message.
      (let [value (try
                    (do-register!)
                    (catch IllegalStateException e
                      (throw (ex-info (frozen-message id) {:id id} e))))]
        (swap! state #(-> %
                          (assoc-in [:declarations entry-key] decl)
                          (assoc-in [:registered entry-key] value)))
        value))))

(defn- flush-block! [id decl]
  (validate-keys! id decl block-keys "block")
  (flush-entry!
   :blocks id decl
   (fn []
     (let [identifier (->identifier id)
           block (Block. (block-properties id decl))]
       (register-entry! BuiltInRegistries/BLOCK
                        (ResourceKey/create Registries/BLOCK identifier) block)
       (when (:item? decl true)
         (let [item-props (doto (Item$Properties.)
                            (.setId (ResourceKey/create Registries/ITEM identifier))
                            (.useBlockDescriptionPrefix))
               block-item (BlockItem. block item-props)]
           (register-entry! BuiltInRegistries/ITEM
                            (ResourceKey/create Registries/ITEM identifier) block-item)
           (swap! state assoc-in [:registered [:items id]] block-item)
           (when-some [group (:group decl)] (add-to-group! group id))))
       block))))

(defn- flush-item! [id decl]
  (validate-keys! id decl item-keys "item")
  (flush-entry!
   :items id decl
   (fn []
     (let [item (Item. (item-properties id decl))]
       (register-entry! BuiltInRegistries/ITEM
                        (ResourceKey/create Registries/ITEM (->identifier id)) item)
       (when-some [group (:group decl)] (add-to-group! group id))
       item))))

(defn register!
  "Registers content from a declarative description (see the ns docstring).
  Returns a map of the content registered by this call
  ({:blocks {id Block} :items {id Item}}), or :skein/aot-compiling during
  AOT compilation."
  [content]
  (when-not (map? content)
    (throw (ex-info (str "register! expects a map like {:blocks {...} :items {...}}, got: " (pr-str content))
                    {:content content})))
  (when-some [unknown (seq (remove #{:blocks :items} (keys content)))]
    (throw (ex-info (str "Unknown register! sections " (vec unknown) ". Supported: [:blocks :items]")
                    {:unknown (vec unknown)})))
  (if *compile-files*
    :skein/aot-compiling
    {:blocks (into {} (keep (fn [[id decl]] (when-some [b (flush-block! id decl)] [id b])))
                   (:blocks content))
     :items (into {} (keep (fn [[id decl]] (when-some [i (flush-item! id decl)] [id i])))
                  (:items content))}))

;;; Introspection / access from logic and the REPL

(defn block
  "The registered Block for an id — for handlers and the REPL."
  ^Block [id]
  (or (get-in @state [:registered [:blocks id]])
      (throw (ex-info (str "No block registered for " id ". Registered blocks: "
                           (vec (sort (keep (fn [[[kind entry-id] _]] (when (= kind :blocks) entry-id))
                                            (:registered @state)))))
                      {:id id}))))

(defn item
  "The registered Item (including the auto BlockItems of blocks) for an id."
  ^Item [id]
  (or (get-in @state [:registered [:items id]])
      (throw (ex-info (str "No item registered for " id ". Registered items: "
                           (vec (sort (keep (fn [[[kind entry-id] _]] (when (= kind :items) entry-id))
                                            (:registered @state)))))
                      {:id id}))))

(defn registered
  "A snapshot of the registrations: {:blocks [id ...] :items [id ...]}
  (for the REPL)."
  []
  (reduce (fn [acc [[kind id] _]] (update acc kind (fnil conj []) id))
          {} (sort-by (comp str first) (:registered @state))))
