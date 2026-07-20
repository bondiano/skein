(ns skein.block
  "BlockState as data: `{:block :minecraft/oak_stairs :props {:facing :north :half :top}}`.

      (->blockstate :minecraft/stone)                    ; default state
      (->blockstate {:block :minecraft/oak_stairs
                     :props {:facing :north :half :top}})
      (blockstate-> a-block-state)                       ; -> the data map

  A property value is a keyword for an enum property (`:north`, `:top`), a
  boolean for a boolean property (`waterlogged`), or an integer for a numeric
  one. The whole conversion is pure once the block registries exist — no world
  or server is involved."
  (:require [skein.id :as id]
            [skein.schema :as schema]
            [skein.schemas :as schemas])
  (:import (net.minecraft.core.registries BuiltInRegistries)
           (net.minecraft.world.level.block Block)
           (net.minecraft.world.level.block.state BlockState)
           (net.minecraft.world.level.block.state.properties Property)))

(defn- block-by-id ^Block [block-id]
  (let [identifier (id/id block-id)
        holder (.get BuiltInRegistries/BLOCK identifier)]
    (if (.isPresent holder)
      (.value ^net.minecraft.core.Holder (.get holder))
      (throw (ex-info (str "No block registered for " (pr-str block-id) " (" identifier ")")
                      {:block block-id})))))

(defn- prop-value-string [v]
  (cond
    (keyword? v) (name v)
    (boolean? v) (str v)
    :else (str v)))

(defn- set-prop ^BlockState [^BlockState state ^Property prop v]
  (let [parsed (.getValue prop (prop-value-string v))]
    (if (.isPresent parsed)
      (.setValue state prop (.get parsed))
      (throw (ex-info (str "Invalid value " (pr-str v) " for block property " (.getName prop)
                           " — allowed: " (mapv #(.getName prop %) (.getPossibleValues prop)))
                      {:property (.getName prop) :value v})))))

(defn ->blockstate
  "Coerce to a BlockState: a BlockState passes through, a bare id keyword gives
  the block's default state, a `{:block :props}` map applies the properties."
  ^BlockState [x]
  (cond
    (instance? BlockState x) x

    (keyword? x) (.defaultBlockState (block-by-id x))

    (map? x)
    (schema/guarded
     schemas/blockstate-map x "blockstate map"
     (fn []
       (let [{:keys [block props]} x
             b (block-by-id block)
             definition (.getStateDefinition b)]
         (reduce-kv
          (fn [^BlockState state prop-key v]
            (let [prop (.getProperty definition (name prop-key))]
              (when (nil? prop)
                (throw (ex-info (str "Unknown property " prop-key " for block " block
                                     " — it has " (mapv #(keyword (.getName ^Property %)) (.getProperties (.defaultBlockState b))))
                                {:block block :property prop-key})))
              (set-prop state prop v)))
          (.defaultBlockState b)
          (or props {})))))

    :else (throw (ex-info (str "Cannot coerce to a BlockState: " (pr-str x)
                               " — expected a BlockState, an id keyword, or a {:block :props} map")
                          {:value x}))))

(defn- prop-value [^Property prop value]
  (let [cls (.getValueClass prop)]
    (cond
      (= Boolean cls) value
      (= Integer cls) value
      :else (keyword (.getName prop value)))))

(defn blockstate->
  "The data map for a BlockState: `{:block id}`, plus `:props` when the block
  has any. The inverse of `->blockstate`."
  [^BlockState state]
  (let [block-id (id/id->kw (.getKey BuiltInRegistries/BLOCK (.getBlock state)))
        props (into {}
                    (map (fn [^Property p] [(keyword (.getName p)) (prop-value p (.getValue state p))]))
                    (.getProperties state))]
    (cond-> {:block block-id}
      (seq props) (assoc :props props))))
