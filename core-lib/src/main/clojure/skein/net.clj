(ns skein.net
  "Custom packets as data: a payload is a Clojure map, its wire format comes
  from its schema, and the handler is a var.

      ;; both sides, from the init entrypoint
      (net/define! :mymod/score {:schema [:map [:score :int]] :dir :s2c})

      ;; the receiving side
      (net/on! :mymod/score #'show-score)

      ;; the sending side
      (net/send! player :mymod/score {:score 42})
      (net/broadcast! server :mymod/score {:score 42})

  A packet declaration names the shape of its payload and its direction:
  `:s2c` (server to client), `:c2s` (client to server) or `:both`. The wire
  codec is derived from the schema (see skein.codec) — there is no second
  description of the same data to keep in sync — and the payload is validated
  before it goes on the wire, so a bad value fails at the `send!` call with a
  path, not inside the network thread.

  Handlers receive one map: the payload under `:data`, plus who it came from
  and which side is handling it.

  - on the server (`:c2s`): `{:data ... :player <ServerPlayer> :server <MinecraftServer> :side :server}`
  - on the client (`:s2c`): `{:data ... :player <LocalPlayer> :client <Minecraft> :side :client}`

  Two handler styles, as everywhere else in the layer: `on!` takes a
  side-effecting handler, `on-pure!` takes a pure one that returns a vector of
  effects (run through skein.fx — against the player's level on the server,
  against the client instance on the client). Fabric already dispatches payload
  handlers onto the game thread, so a handler may touch the game directly.

  Hot reload: `on!` stores the var, and the receiver Fabric holds reads it on
  every packet — redefine the handler and the next packet runs the new code.
  Re-evaluating the declaring namespace is a no-op; the *shape* of a packet (its
  schema or direction) is fixed for the run, like registry content, because the
  payload type is registered once at startup.

  Declare packets from the init entrypoint — the payload type has to exist
  before a player connects. Needs fabric-api (the networking module). Client
  classes are reached reflectively, so this namespace also loads on a dedicated
  server, where they do not exist."
  (:require [malli.core :as m]
            [skein.codec :as codec]
            [skein.fx :as fx]
            [skein.id :as id]
            [skein.interop :as interop]
            [skein.schema :as schema])
  (:import (java.lang.reflect InvocationHandler Method Proxy)
           (java.util.function Function)
           (net.minecraft.network.codec ByteBufCodecs StreamCodec)
           (net.minecraft.network.protocol.common.custom CustomPacketPayload CustomPacketPayload$Type)
           (net.minecraft.server MinecraftServer)
           (net.minecraft.server.level ServerLevel ServerPlayer)))

(defonce ^:private ^org.slf4j.Logger logger
  (org.slf4j.LoggerFactory/getLogger "skein"))

(def declaration
  "A packet declaration: the shape of the payload and the direction it travels."
  [:map {:closed true}
   [:schema :any]
   [:dir [:enum {:error/message "should be :s2c, :c2s or :both"} :s2c :c2s :both]]])

(defonce ^:private packets
  ;; id -> {:decl :type :codec :schema :valid?
  ;;        :handlers {side {:handler var :style :fn|:pure}} :installed #{side}}.
  ;; A defonce registry: the payload type is registered with the game once, and
  ;; a namespace reload only swaps the handler vars inside it.
  (atom {}))

;;; The payload object
;;;
;;; The game moves a CustomPacketPayload; the mod only ever sees the data it
;;; carries. The object is a thin holder: it answers with its type, and derefs
;;; to the Clojure value.

(defn- payload
  ^CustomPacketPayload [^CustomPacketPayload$Type packet-type value]
  (reify
    CustomPacketPayload
    (type [_] packet-type)
    clojure.lang.IDeref
    (deref [_] value)))

(defn- payload-codec
  "The wire codec for a packet: the schema's codec, wrapped so what travels is
  the payload object and what the mod handles is the value."
  ^StreamCodec [schema ^CustomPacketPayload$Type packet-type]
  (.map (ByteBufCodecs/fromCodecWithRegistries (codec/of schema))
        (reify Function (apply [_ value] (payload packet-type value)))
        (reify Function (apply [_ p] (deref p)))))

;;; The client half of fabric-api, reached reflectively
;;;
;;; Loading this namespace on a dedicated server must not touch a client class,
;;; and a compiled reference would (the compiler resolves it at load). Every
;;; client call therefore goes through Class/forName, guarded by an environment
;;; check that produces an actionable error instead of a NoClassDefFoundError.

(def ^:private client-networking-class "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")

(defn- client-class
  ^Class [class-name what]
  (try
    (Class/forName class-name false (.getClassLoader clojure.lang.RT))
    (catch ClassNotFoundException e
      (throw (ex-info (str what " is client-side: this JVM runs a dedicated server, which ships no client classes."
                           " Register client handlers and send client-to-server packets from the mod's client"
                           " entrypoint.")
                      {} e)))))

(defn- ensure-client! [what]
  (when-not (interop/client-env?)
    (throw (ex-info (str what " is client-side, and this JVM runs a dedicated server."
                         " Move it to the mod's client entrypoint.")
                    {}))))

(defn- client-invoke!
  "A static call on ClientPlayNetworking, resolved at call time."
  [method what args]
  (ensure-client! what)
  (clojure.lang.Reflector/invokeStaticMethod
   (client-class client-networking-class what) ^String method (object-array args)))

(defn- client-ctx-value
  "A field of the client-side handler context (`client`, `player`)."
  [ctx accessor]
  (clojure.lang.Reflector/invokeInstanceMethod ctx ^String accessor (object-array 0)))

;;; Handler dispatch

(defn- handler-context
  "The map a handler receives: the payload plus where it arrived. The server
  context is a plain interface call (the class exists on both sides); the
  client one goes through reflection, like every other client-side reach."
  [side data ctx]
  (case side
    :server (let [^net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$Context c ctx]
              {:data data :player (.player c) :server (.server c) :side :server})
    :client {:data data
             :player (client-ctx-value ctx "player")
             :client (client-ctx-value ctx "client")
             :side :client}))

(defn- effect-context
  "What a pure handler's effects run against: the player's level on the server
  (so world effects work), the client instance on the client."
  [side handler-map]
  (if (= :server side)
    (some-> ^ServerPlayer (:player handler-map) .level)
    (:client handler-map)))

(defn- deliver!
  "Runs the handler currently registered for [id side] — looked up on every
  packet, which is what makes it hot-reloadable."
  [id side data ctx]
  (when-some [{:keys [handler style]} (get-in @packets [id :handlers side])]
    (let [handler-map (handler-context side data ctx)]
      (if (= :pure style)
        (when-some [effects (handler handler-map)]
          (fx/run-effects! (effect-context side handler-map) effects))
        (handler handler-map))
      nil)))

;;; Declaration

(defn- ensure-fabric-api! [id]
  (try
    (Class/forName "net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry"
                   false (.getClassLoader clojure.lang.RT))
    (catch ClassNotFoundException e
      (throw (ex-info (str "Cannot declare the packet " id
                           ": skein.net wraps the Fabric networking API, but fabric-api is not on the"
                           " classpath. Add it to the mod's dependencies.")
                      {:packet id} e)))))

(defn- register-type!
  "Registers the payload type in the codec registries its direction needs.
  Both sides register both directions of a packet they take part in — the
  registry is a description of the protocol, not of who sends."
  [dir ^CustomPacketPayload$Type type ^StreamCodec codec]
  (doseq [registry (case dir
                     :s2c [(net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry/clientboundPlay)]
                     :c2s [(net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry/serverboundPlay)]
                     :both [(net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry/clientboundPlay)
                            (net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry/serverboundPlay)])]
    (.register ^net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry registry type codec)))

(defn define!
  "Declares a packet and returns its id (see the ns docstring).

  Options: `:schema` (a Malli schema — the payload's shape and its wire format)
  and `:dir` (`:s2c`, `:c2s` or `:both`).

  Idempotent per id: re-evaluating the declaring namespace keeps the registered
  type; a *changed* declaration logs a warning and keeps the old one, because
  the payload type is registered with the game at startup. During AOT
  compilation the call is a no-op returning :skein/aot-compiling."
  [id {:keys [schema dir] :as decl}]
  (schema/validate! declaration decl "packet declaration")
  (when-not (and (keyword? id) (namespace id))
    (throw (ex-info (str "A packet id must be a namespace-qualified keyword like :mymod/sync, got: "
                         (pr-str id))
                    {:packet id})))
  ;; Derive the codec here, so an unstorable schema is an error in the
  ;; entrypoint rather than the first time the packet is sent.
  (codec/of schema)
  (if *compile-files*
    :skein/aot-compiling
    (do
      (ensure-fabric-api! id)
      (if-some [existing (get @packets id)]
        (do
          (when (not= (:decl existing) decl)
            (.warn logger "[skein] packet {} is already declared; its shape is fixed for this run — restart the game to apply the changed declaration"
                   (str id)))
          id)
        (let [packet-type (CustomPacketPayload$Type. (id/id id))
              wire (payload-codec schema packet-type)]
          (register-type! dir packet-type wire)
          (swap! packets assoc id {:decl decl
                                   :type packet-type
                                   :codec wire
                                   ;; compiled once: sending validates on the fast
                                   ;; path and only builds a message on a mismatch
                                   :schema (m/schema schema)
                                   :valid? (m/validator schema)
                                   :handlers {}
                                   :installed #{}})
          id)))))

(defn- packet
  "The declared packet, or an actionable error naming what is declared."
  [id]
  (or (get @packets id)
      (throw (ex-info (str "No packet declared for " id
                           ". Declare it from the init entrypoint with (net/define! " id " {...})."
                           " Declared: " (vec (sort (keys @packets))))
                      {:packet id}))))

(defn- receiving-sides
  "Which side handles this packet here: the server receives `:c2s`, the client
  receives `:s2c`, and `:both` is handled by whichever sides exist in this JVM
  (a client with an integrated server is both)."
  [dir]
  (case dir
    :s2c [:client]
    :c2s [:server]
    :both (if (interop/client-env?) [:client :server] [:server])))

;;; Receiving

(defn- server-receiver
  "The Fabric handler object registered once per packet on the server; it looks
  the current handler var up on every packet."
  [id]
  (reify net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayPayloadHandler
    (receive [_ payload ctx] (deliver! id :server (deref payload) ctx))))

(defn- client-receiver
  "The same, on the client — through a Proxy, so the interface class is only
  loaded where it exists."
  [id]
  (let [^Class handler-class (client-class (str client-networking-class "$PlayPayloadHandler")
                                           "A client packet handler")]
    (Proxy/newProxyInstance
     (.getClassLoader handler-class)
     (into-array Class [handler-class])
     (reify InvocationHandler
       (invoke [_ proxy method args]
         (case (.getName ^Method method)
           "receive" (deliver! id :client (deref (aget ^objects args 0)) (aget ^objects args 1))
           "toString" (str "skein packet receiver " id)
           "hashCode" (int (System/identityHashCode proxy))
           "equals" (identical? proxy (aget ^objects args 0))
           nil))))))

(defn- install-receiver!
  "Registers the Fabric receiver for [id side] once. Later `on!` calls only
  swap the handler var the receiver reads."
  [id side]
  (when-not (contains? (:installed (packet id)) side)
    (let [type (:type (packet id))]
      (if (= :server side)
        (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking/registerGlobalReceiver
         ^CustomPacketPayload$Type type (server-receiver id))
        (client-invoke! "registerGlobalReceiver" "A client packet handler"
                        [type (client-receiver id)]))
      (swap! packets update-in [id :installed] conj side))))

(defn- register-handler! [id handler-var style]
  (when-not (var? handler-var)
    (throw (ex-info (str "The handler for " id " must be a var (#'my-handler), got: " (pr-str handler-var)
                         ". Registering the var — not the fn — is what makes the handler hot-reloadable.")
                    {:packet id :handler handler-var})))
  (let [{:keys [decl]} (packet id)]
    (doseq [side (receiving-sides (:dir decl))]
      (swap! packets assoc-in [id :handlers side] {:handler handler-var :style style})
      (install-receiver! id side)))
  id)

(defn on!
  "Registers a var as the packet's handler on the side that receives it (see
  the ns docstring for the map it gets). Registering again with another var
  replaces the handler; the receiver Fabric holds derefs the current var on
  every packet, so redefining the handler takes effect immediately."
  [id handler-var]
  (register-handler! id handler-var :fn))

(defn on-pure!
  "Registers a *pure* handler var: a function of the handler map that returns a
  vector of effects (see skein.fx), run against the player's level on the
  server and the client instance on the client. A nil return runs nothing."
  [id handler-var]
  (register-handler! id handler-var :pure))

;;; Sending

(defn- check-direction!
  "`wanted` is the direction this send needs; a packet declared `:both` travels
  either way."
  [id wanted what]
  (let [dir (:dir (:decl (packet id)))]
    (when-not (contains? #{wanted :both} dir)
      (throw (ex-info (str "Packet " id " is declared :dir " dir ", so it cannot be " what "."
                           " Declare it :dir " wanted " or :both.")
                      {:packet id :dir dir})))))

(defn ->payload
  "The payload object the game moves, built from a declared packet and its data
  (validated on the way in). `send!` and friends do this for you; reach for it
  when a Fabric API wants a `CustomPacketPayload` directly."
  ^CustomPacketPayload [id data]
  (let [{:keys [type schema valid?]} (packet id)]
    ;; Validate at the call site: an error here names the field, whereas a
    ;; failure while the packet is being written would surface far from the
    ;; code that built it. The validator is the fast path — the explanation is
    ;; only built when the value is actually wrong.
    (when-not (valid? data)
      (schema/validate! schema data (str "payload of " id)))
    (payload type data)))

(defn- ->server-player
  ^ServerPlayer [target]
  (let [obj (if (map? target) (:skein/obj (meta target)) target)]
    (if (instance? ServerPlayer obj)
      obj
      (throw (ex-info (str "Cannot send to " (pr-str target)
                           " — expected a ServerPlayer (or a player snapshot).")
                      {:target target})))))

(defn send!
  "Sends a packet to one player (a ServerPlayer or a player snapshot), or to
  every player in a collection. Server side."
  [target id data]
  (check-direction! id :s2c "sent to a player")
  (let [p (->payload id data)]
    (doseq [player (if (or (sequential? target) (set? target)) target [target])]
      (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking/send (->server-player player) p))
    id))

(defn broadcast!
  "Sends a packet to every player on the server, or to every player in one
  level. Server side."
  [target id data]
  (check-direction! id :s2c "broadcast")
  (let [p (->payload id data)
        ;; `instance?` on a class *name* compiles to a plain instanceof; the
        ;; same classes used as class *literals* (a condp dispatch value, say)
        ;; would be baked into this function's class initializer and force the
        ;; game class to initialize when the namespace loads — which fails
        ;; outside a booted game, AOT compilation included.
        players (cond
                  (instance? ServerLevel target) (.players ^ServerLevel target)
                  (instance? MinecraftServer target) (.getPlayers (.getPlayerList ^MinecraftServer target))
                  :else (throw (ex-info (str "Cannot broadcast to " (pr-str target)
                                             " — expected a MinecraftServer or a ServerLevel.")
                                        {:target target})))]
    (doseq [player players]
      (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking/send ^ServerPlayer player p))
    id))

(defn to-server!
  "Sends a packet from the client to the server. Client side."
  [id data]
  (check-direction! id :c2s "sent to the server")
  (client-invoke! "send" "Sending a packet to the server" [(->payload id data)])
  id)

;;; The wire, in the open — what the game does with a payload, callable
;;; directly. For tests and for the REPL: see what a packet actually costs in
;;; bytes, or check that a schema change still reads old data.

(defn encode!
  "Writes a packet's payload into a buffer exactly as the game would put it on
  the wire. The buffer must be registry-aware (`RegistryFriendlyByteBuf`), since
  a payload may carry registry content."
  [id data buf]
  (.encode ^StreamCodec (:codec (packet id)) buf (->payload id data))
  buf)

(defn decode
  "Reads a payload back out of a buffer, as the receiving side does."
  [id buf]
  (deref (.decode ^StreamCodec (:codec (packet id)) buf)))

;;; Introspection

(defn payload-type
  "The game's `CustomPacketPayload$Type` for a declared packet — for the Fabric
  APIs that take one (`canSend`, bundling a packet by hand)."
  ^CustomPacketPayload$Type [id]
  (:type (packet id)))

(defn declared
  "What is declared, as data: id -> {:dir ... :schema ... :handlers {side var}}
  (for the REPL)."
  []
  (into (sorted-map)
        (map (fn [[id {:keys [decl handlers]}]]
               [id (assoc decl :handlers (into {} (map (fn [[side h]] [side (:handler h)])) handlers))]))
        @packets))

;;; Effects as data (layer B) — thin delegation to the `!`-functions above.

(defmethod fx/fx! :send-packet [_ctx [_ target id data]] (send! target id data))
(defmethod fx/fx! :broadcast-packet [ctx [_ id data]] (broadcast! ctx id data))
