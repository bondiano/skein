(ns skein.codec
  "Clojure data <-> the game's serialization format, derived from a Malli schema.

      (def scores [:map-of :uuid :int])

      (codec/->nbt scores {#uuid\"...\" 7})   ;=> a CompoundTag
      (codec/nbt-> scores tag)                ;=> {#uuid\"...\" 7}
      (codec/of scores)                       ;=> a Codec the game can use

  The game persists everything (world save data, entity attachments, network
  payloads) through Mojang `Codec`s. Writing one by hand means a second
  description of the same shape, one that drifts from the schema that validates
  it — so this namespace derives the codec *from* the schema: one description of
  the data, used for validation, for the error messages, and for the bytes on
  disk.

  What a schema may contain:
  - maps — `:map` with declared entries (an entry marked `{:optional true}` or
    typed `[:maybe ...]` is omitted from the output when its value is nil) and
    `:map-of` (whose keys must be strings, keywords, uuids or integers, since
    the format's keys are strings);
  - collections — `:vector`, `:sequential`, `:set`, `:tuple`;
  - leaves — `:string`, `:keyword`, `:uuid`, `:int`, `:double`, `:boolean`,
    `:enum`, and the predicate spellings (`string?`, `int?`, ...);
  - `:and` (encoded as its first branch — the extra predicates only constrain);
  - `:any` — an escape hatch: the value is stored as EDN text. Anything Clojure
    can `pr-str` round-trips, at the cost of being opaque to other tools.

  A schema the derivation cannot encode (`:or`, `:multi`, a bare `:fn`) fails
  fast at derivation time with a message naming the schema and the way out —
  never with a half-written save file.

  Values are validated on the way in and on the way out: encoding a value that
  does not match the schema is an error naming the path, and so is decoding a
  saved file whose shape no longer matches (a renamed field, a changed type)."
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [skein.schema :as schema])
  (:import (com.mojang.datafixers.util Pair)
           (com.mojang.serialization Codec DataResult DynamicOps MapLike)
           (java.util ArrayList HashMap UUID)
           (java.util.function Function Supplier)))

;;; DataResult plumbing — the game's API answers with a DataResult; inside the
;;; derivation we work with values and exceptions, and translate at the edges.

(defn- unwrap
  "The value of a DataResult, or an ex-info explaining what was expected."
  [^DataResult result expectation]
  (.getOrThrow result (reify Function
                        (apply [_ message]
                          (ex-info (str "Expected " expectation " in the stored data: " message)
                                   {:skein/codec-error true :expected expectation})))))

(defn- error-result
  ^DataResult [^String message]
  (DataResult/error (reify Supplier (get [_] message))))

;;; Leaves

(defn- kw->str [k] (subs (str k) 1))

(defn- string-leaf
  "A leaf stored as a string, given the two conversions."
  [->s s-> expectation]
  {:enc (fn [v ^DynamicOps ops] (.createString ops ^String (->s v)))
   :dec (fn [t ^DynamicOps ops] (s-> (unwrap (.getStringValue ops t) expectation)))})

(def ^:private leaves
  (let [string {:enc (fn [v ^DynamicOps ops] (.createString ops ^String v))
                :dec (fn [t ^DynamicOps ops] (unwrap (.getStringValue ops t) "a string"))}
        keyword* (string-leaf kw->str keyword "a keyword")
        uuid (string-leaf str #(UUID/fromString %) "a uuid")
        edn (string-leaf pr-str edn/read-string "EDN text")
        int* {:enc (fn [v ^DynamicOps ops] (.createLong ops (long v)))
              :dec (fn [t ^DynamicOps ops] (.longValue ^Number (unwrap (.getNumberValue ops t) "a number")))}
        double* {:enc (fn [v ^DynamicOps ops] (.createDouble ops (double v)))
                 :dec (fn [t ^DynamicOps ops] (.doubleValue ^Number (unwrap (.getNumberValue ops t) "a number")))}
        number {:enc (fn [v ^DynamicOps ops]
                       (if (integer? v) (.createLong ops (long v)) (.createDouble ops (double v))))
                :dec (fn [t ^DynamicOps ops]
                       (let [^Number n (unwrap (.getNumberValue ops t) "a number")]
                         (if (or (instance? Long n) (instance? Integer n) (instance? Short n) (instance? Byte n))
                           (long n)
                           (double n))))}
        boolean* {:enc (fn [v ^DynamicOps ops] (.createBoolean ops (boolean v)))
                  :dec (fn [t ^DynamicOps ops] (unwrap (.getBooleanValue ops t) "a boolean"))}]
    {:string string, 'string? string
     :keyword keyword*, 'keyword? keyword*
     :qualified-keyword keyword*, 'qualified-keyword? keyword*
     :simple-keyword keyword*, 'simple-keyword? keyword*
     :uuid uuid, 'uuid? uuid
     :int int*, 'int? int*, 'integer? int*, 'pos-int? int*, 'nat-int? int*, 'neg-int? int*
     :double double*, 'double? double*, 'float? double*
     :boolean boolean*, 'boolean? boolean*
     'number? number
     :any edn, 'any? edn, :some edn, 'some? edn}))

;;; Deriving the codec from the schema

(declare compile-schema)

(defn- unsupported! [s]
  (throw (ex-info (str "skein.codec cannot store " (pr-str (m/form s)) ".\n"
                       "  Storable: :map, :map-of, :vector, :sequential, :set, :tuple, :maybe, :enum,\n"
                       "            :string, :keyword, :uuid, :int, :double, :boolean (and the string?/int?/... spellings).\n"
                       "  Ways out: describe the alternatives as named fields of a :map instead of an :or,\n"
                       "            or type the field :any to store it as EDN text.")
                  {:schema (m/form s)})))

(defn- map-key-codec
  "The conversions for a `:map-of` key. The stored format keys maps by string,
  so a key type must have a lossless string spelling."
  [key-schema]
  (case (m/type key-schema)
    (:string string?) {:->s identity :s-> identity}
    (:keyword keyword? :qualified-keyword qualified-keyword? :simple-keyword simple-keyword?)
    {:->s kw->str :s-> keyword}
    (:uuid uuid?) {:->s str :s-> #(UUID/fromString %)}
    (:int int? integer? pos-int? nat-int?) {:->s str :s-> #(Long/parseLong %)}
    (throw (ex-info (str "A :map-of key must be a string, keyword, uuid or int — stored data keys maps by name."
                         " Got " (pr-str (m/form key-schema)) "."
                         " Store the pairs as a :vector of [key value] :tuple entries if the key cannot be a name.")
                    {:schema (m/form key-schema)}))))

(defn- entry-specs
  "One spec per declared entry of a `:map` schema: where the value lives in the
  Clojure map, what it is called in the stored data, and what a missing field
  decodes to — an entry marked `{:optional true}` is left out of the decoded
  map, a `[:maybe ...]` entry comes back as an explicit nil (the schema wants
  the key there), and anything else is required."
  [s]
  (mapv (fn [[k props child]]
          (let [child (m/schema child)
                {:keys [enc dec]} (compile-schema child)]
            {:key k
             :field (kw->str k)
             :when-missing (cond
                             (:optional props) :skip
                             (= :maybe (m/type child)) :nil
                             :else :required)
             :enc enc
             :dec dec}))
        (m/children s)))

(defn- map-codec [s]
  (let [entries (entry-specs s)]
    {:enc (fn [v ^DynamicOps ops]
            (let [fields (HashMap.)]
              (doseq [{:keys [key field when-missing enc]} entries]
                (let [x (get v key)]
                  (cond
                    (some? x) (.put fields (.createString ops ^String field) (enc x ops))
                    (= :required when-missing)
                    (throw (ex-info (str "Cannot store the value: the required field " key " is missing")
                                    {:skein/codec-error true :field key})))))
              (.createMap ops fields)))
     :dec (fn [t ^DynamicOps ops]
            (let [^MapLike fields (unwrap (.getMap ops t) "a map")]
              (reduce (fn [acc {:keys [key field when-missing dec]}]
                        (if-some [raw (.get fields ^String field)]
                          (assoc acc key (dec raw ops))
                          (case when-missing
                            :skip acc
                            :nil (assoc acc key nil)
                            (throw (ex-info (str "The stored data is missing the required field " key
                                                 " (stored as \"" field "\")")
                                            {:skein/codec-error true :field key})))))
                      {} entries)))}))

(defn- map-of-codec [s]
  (let [[key-schema val-schema] (m/children s)
        {:keys [->s s->]} (map-key-codec (m/schema key-schema))
        {:keys [enc dec]} (compile-schema (m/schema val-schema))]
    {:enc (fn [v ^DynamicOps ops]
            (let [fields (HashMap.)]
              (doseq [[k x] v]
                (.put fields (.createString ops ^String (->s k)) (enc x ops)))
              (.createMap ops fields)))
     :dec (fn [t ^DynamicOps ops]
            (let [^MapLike fields (unwrap (.getMap ops t) "a map")]
              (into {}
                    (map (fn [^Pair pair]
                           [(s-> (unwrap (.getStringValue ops (.getFirst pair)) "a map key"))
                            (dec (.getSecond pair) ops)]))
                    (iterator-seq (.iterator (.entries fields))))))}))

(defn- seq-codec
  "A homogeneous collection; `into` decides what the decoded value is (a vector
  for the sequential schemas, a set for `:set`)."
  [s empty-coll]
  (let [{:keys [enc dec]} (compile-schema (m/schema (first (m/children s))))]
    {:enc (fn [v ^DynamicOps ops]
            (.createList ops (.stream (ArrayList. ^java.util.Collection (mapv #(enc % ops) v)))))
     :dec (fn [t ^DynamicOps ops]
            (into empty-coll
                  (map #(dec % ops))
                  (iterator-seq (.iterator ^java.util.stream.Stream
                                           (unwrap (.getStream ops t) "a list")))))}))

(defn- tuple-codec [s]
  (let [parts (mapv (comp compile-schema m/schema) (m/children s))]
    {:enc (fn [v ^DynamicOps ops]
            (when-not (= (count parts) (count v))
              (throw (ex-info (str "Cannot store the value: expected " (count parts)
                                   " tuple elements, got " (count v))
                              {:skein/codec-error true})))
            (.createList ops (.stream (ArrayList. ^java.util.Collection
                                                  (into [] (map (fn [{:keys [enc]} x] (enc x ops)) parts v))))))
     :dec (fn [t ^DynamicOps ops]
            (let [raw (vec (iterator-seq (.iterator ^java.util.stream.Stream
                                                    (unwrap (.getStream ops t) "a list"))))]
              (when-not (= (count parts) (count raw))
                (throw (ex-info (str "The stored data has " (count raw) " tuple elements, expected " (count parts))
                                {:skein/codec-error true})))
              (into [] (map (fn [{:keys [dec]} x] (dec x ops)) parts raw))))}))

(defn- enum-codec
  "An enum of keywords or strings, stored by name; an unknown name names the
  members it could have been."
  [s]
  (let [members (m/children s)
        ->s (fn [v] (if (keyword? v) (kw->str v) (str v)))
        by-name (into {} (map (juxt ->s identity)) members)]
    {:enc (fn [v ^DynamicOps ops] (.createString ops ^String (->s v)))
     :dec (fn [t ^DynamicOps ops]
            (let [name* (unwrap (.getStringValue ops t) "an enum name")]
              (if (contains? by-name name*)
                (get by-name name*)
                (throw (ex-info (str "The stored data has " (pr-str name*)
                                     " where the schema allows " (pr-str (vec members)))
                                {:skein/codec-error true :value name*})))))}))

(defn- maybe-codec
  "`[:maybe X]` outside a map entry: an empty list is nil, a one-element list is
  the value. As a map entry value it never reaches here — the entry is simply
  left out (see `entry-specs`)."
  [s]
  (let [{:keys [enc dec]} (compile-schema (m/schema (first (m/children s))))]
    {:enc (fn [v ^DynamicOps ops]
            (.createList ops (.stream (ArrayList. ^java.util.Collection (if (nil? v) [] [(enc v ops)])))))
     :dec (fn [t ^DynamicOps ops]
            (let [raw (vec (iterator-seq (.iterator ^java.util.stream.Stream
                                                    (unwrap (.getStream ops t) "a list"))))]
              (when (seq raw) (dec (first raw) ops))))}))

(defn- compile-schema
  "The {:enc :dec} pair for a schema: `enc` turns a Clojure value into the
  format's representation, `dec` reads it back. Both take the DynamicOps of the
  target format, so the same derivation serves NBT on disk and JSON in a
  datapack."
  [s]
  (let [t (m/type s)]
    (or (get leaves t)
        (case t
          :map (map-codec s)
          :map-of (map-of-codec s)
          (:vector :sequential :seqable) (seq-codec s [])
          :set (seq-codec s #{})
          :tuple (tuple-codec s)
          :enum (enum-codec s)
          :maybe (maybe-codec s)
          :and (compile-schema (m/schema (first (m/children s))))
          (:schema :ref ::m/schema) (compile-schema (m/deref s))
          (unsupported! s)))))

;;; The codec

(defn of
  "The `Codec` for a Malli schema, over ordinary Clojure data (see the ns
  docstring for what a schema may contain). Derive it once — at declaration
  time, not per call — and hand it to the game."
  ^Codec [schema]
  (let [s (m/schema schema)
        {:keys [enc dec]} (compile-schema s)
        valid? (m/validator s)]
    (reify Codec
      (encode [_ value ops prefix]
        (try
          (when-not (valid? value)
            (throw (ex-info (str "The value does not match its schema:\n" (schema/explain-str s value))
                            {:skein/codec-error true})))
          (.mergeToPrimitive ^DynamicOps ops prefix (enc value ops))
          (catch Throwable e
            (error-result (.getMessage e)))))
      (decode [_ ops input]
        (try
          (let [value (dec input ops)]
            (if (valid? value)
              (DataResult/success (Pair. value (.empty ^DynamicOps ops)))
              (error-result (str "the stored data does not match its schema:\n"
                                 (schema/explain-str s value)))))
          (catch Throwable e
            (error-result (.getMessage e))))))))

;;; NBT — the format the world save and entity attachments use

(defn ->nbt
  "The value as an NBT Tag, per its schema. Throws an actionable error when the
  value does not match."
  [schema value]
  (let [^Codec codec (of schema)]
    (.getOrThrow (.encodeStart codec net.minecraft.nbt.NbtOps/INSTANCE value)
                 (reify Function
                   (apply [_ message]
                     (ex-info (str "Cannot store the value as NBT: " message)
                              {:skein/codec-error true :value value}))))))

(defn nbt->
  "The value read back from an NBT Tag, per its schema. Throws an actionable
  error when the stored data no longer matches the schema."
  [schema tag]
  (let [^Codec codec (of schema)]
    (.getOrThrow (.parse codec net.minecraft.nbt.NbtOps/INSTANCE tag)
                 (reify Function
                   (apply [_ message]
                     (ex-info (str "Cannot read the stored NBT: " message)
                              {:skein/codec-error true}))))))
