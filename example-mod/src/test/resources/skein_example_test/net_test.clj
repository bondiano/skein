(ns skein-example-test.net-test
  "Packets against the live dedicated server: a declared payload type really
  registers, a payload really survives a round trip through a registry-aware
  buffer, and a packet handed to the receiver Fabric holds really reaches the
  handler var. Booted and handed over by skein.example.L2ServerTests.

  What a headless server cannot do is have a client connected to it, so the
  delivery test drives the registered receiver directly — the same object, with
  the same payload, that a real connection would hand it."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.net :as net])
  (:import (io.netty.buffer Unpooled)
           (net.fabricmc.fabric.api.networking.v1 ServerPlayNetworking)
           (net.minecraft.network RegistryFriendlyByteBuf)
           (net.minecraft.resources Identifier)
           (net.minecraft.server MinecraftServer)))

(defonce ^:private the-server (atom nil))

(defn set-server! [^MinecraftServer server] (reset! the-server server))

(defn- server ^MinecraftServer [] @the-server)

(defn- buffer
  "An empty registry-aware buffer, the kind a play packet is written into."
  ^RegistryFriendlyByteBuf []
  (RegistryFriendlyByteBuf. (Unpooled/buffer) (.registryAccess (server))))

;;; The wire

(deftest a-payload-round-trips-through-a-real-buffer
  (let [id (net/define! :skein_example/wire-test
                        {:schema [:map [:score :int] [:tags [:set :keyword]] [:note {:optional true} :string]]
                         :dir :s2c})
        value {:score 42 :tags #{:a :mymod/b}}
        buf (net/encode! id value (buffer))]
    (is (pos? (.readableBytes buf)) "the payload wrote bytes")
    (is (= value (net/decode id buf)) "and reads back as the same data")
    (testing "an optional field travels when present"
      (let [with-note (assoc value :note "hi")]
        (is (= with-note (net/decode id (net/encode! id with-note (buffer)))))))))

(deftest a-payload-that-does-not-match-never-reaches-the-wire
  (let [id :skein_example/wire-test]
    (is (thrown? Exception (net/encode! id {:score "high" :tags #{}} (buffer)))
        "the schema check happens before anything is written")))

;;; Receivers

(defonce ^:private received (atom nil))

(defn record-packet! [handler-map] (reset! received handler-map))
(defn record-twice! [handler-map] (reset! received (assoc handler-map :second true)))

(deftest a-handler-registers-a-real-receiver
  (net/define! :skein_example/up-test {:schema [:map [:n :int]] :dir :c2s})
  (net/on! :skein_example/up-test #'record-packet!)
  (is (contains? (ServerPlayNetworking/getGlobalReceivers)
                 (Identifier/fromNamespaceAndPath "skein_example" "up-test"))
      "the server has a receiver for the packet")
  (testing "registering another var swaps the handler"
    (net/on! :skein_example/up-test #'record-twice!)
    (is (= #'record-twice! (get-in (net/declared) [:skein_example/up-test :handlers :server])))
    (net/on! :skein_example/up-test #'record-packet!)))

(deftest a-received-packet-reaches-the-handler-as-data
  (net/define! :skein_example/up-test {:schema [:map [:n :int]] :dir :c2s})
  (net/on! :skein_example/up-test #'record-packet!)
  (reset! received nil)
  ;; Take the registered receiver back out of Fabric and hand it a payload, the
  ;; way a connected client's packet would: same handler object, same payload.
  (let [receiver (ServerPlayNetworking/unregisterGlobalReceiver
                  (Identifier/fromNamespaceAndPath "skein_example" "up-test"))
        ;; Through the wire and back, so the payload the receiver gets is the
        ;; one a decoded packet would be.
        data (net/decode :skein_example/up-test (net/encode! :skein_example/up-test {:n 7} (buffer)))]
    (is (some? receiver) "the receiver was registered")
    (try
      (.receive receiver
                (net/->payload :skein_example/up-test data)
                (reify net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$Context
                  (server [_] (server))
                  (player [_] nil)
                  (responseSender [_] nil)))
      (finally
        ;; Put it back so a later run of this suite still has its receiver.
        (ServerPlayNetworking/registerGlobalReceiver
         ^net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type
         (net/payload-type :skein_example/up-test)
         receiver)))
    (is (= {:n 7} (:data @received)) "the handler saw the payload as data")
    (is (= :server (:side @received)) "and knows which side handled it")
    (is (identical? (server) (:server @received)))))

;;; Sending

(deftest broadcasting-with-nobody-online-is-a-no-op
  (let [id (net/define! :skein_example/broadcast-test {:schema [:map [:n :int]] :dir :s2c})]
    (is (= id (net/broadcast! (server) id {:n 1})) "the real send path runs, with no players to reach")
    (is (= id (net/broadcast! (.overworld (server)) id {:n 1})) "a level is a broadcast target too")))
