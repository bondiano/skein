(ns skein.net-test
  "Unit tests for packets-as-data: declaration, the direction guards and the
  errors a bad declaration or a bad payload produces. Registering a receiver
  and moving bytes need a running game and live in the integration test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.net :as net]))

(defn- why [f]
  (try (f) nil (catch Exception e (.getMessage e))))

(defn- fresh-id
  "An id no packet has been declared under yet — a payload type is registered
  with the game once per id, so a test about a *new* packet needs a new id."
  [label]
  (keyword "skein_net_test" (str label "_" (System/nanoTime))))

(deftest a-packet-is-declared-with-a-shape-and-a-direction
  (let [id (fresh-id "sync")]
    (is (= id (net/define! id {:schema [:map [:score :int]] :dir :s2c})))
    (is (= {:schema [:map [:score :int]] :dir :s2c :handlers {}}
           (get (net/declared) id)))))

(deftest re-declaring-is-a-no-op
  (let [id (fresh-id "stable")
        decl {:schema :int :dir :c2s}]
    (net/define! id decl)
    (is (= id (net/define! id decl)) "the same declaration registers once")
    (testing "a changed shape keeps the registered one — the type is fixed for the run"
      (net/define! id {:schema :string :dir :c2s})
      (is (= :int (:schema (get (net/declared) id)))))))

(deftest a-bad-declaration-explains-itself
  (testing "an id that is not namespace-qualified"
    (is (str/includes? (why #(net/define! :sync {:schema :int :dir :s2c})) ":mymod/sync")))
  (testing "an unknown direction"
    (let [message (why #(net/define! (fresh-id "bad-dir") {:schema :int :dir :up}))]
      (is (str/includes? message "[:dir]"))
      (is (str/includes? message ":s2c, :c2s or :both"))))
  (testing "a schema that cannot go on the wire fails at declaration"
    (let [message (why #(net/define! (fresh-id "bad-schema") {:schema [:or :int :string] :dir :s2c}))]
      (is (str/includes? message ":or"))))
  (testing "a misspelled option"
    (is (str/includes? (why #(net/define! (fresh-id "typo") {:schema :int :dir :s2c :direction :s2c}))
                       ":direction"))))

(deftest a-packet-can-only-travel-the-way-it-was-declared
  (let [down (net/define! (fresh-id "down") {:schema :int :dir :s2c})
        up (net/define! (fresh-id "up") {:schema :int :dir :c2s})]
    (testing "a clientbound packet cannot be sent to the server"
      (let [message (why #(net/to-server! down 1))]
        (is (str/includes? message ":dir :s2c"))
        (is (str/includes? message ":dir :c2s or :both"))))
    (testing "a serverbound packet cannot be sent to a player"
      (is (str/includes? (why #(net/send! nil up 1)) ":dir :c2s")))))

(deftest a-payload-is-checked-against-its-schema-at-the-call-site
  (let [id (net/define! (fresh-id "checked") {:schema [:map [:score :int]] :dir :s2c})
        message (why #(net/send! nil id {:score "high"}))]
    (is (str/includes? message "[:score]"))
    (is (str/includes? message "\"high\"") "the offending value is in the message")))

(deftest an-undeclared-packet-names-what-is-declared
  (is (str/includes? (why #(net/send! nil :nope/nope 1)) "No packet declared")))

(deftest a-handler-must-be-a-var
  (let [id (net/define! (fresh-id "handled") {:schema :int :dir :c2s})
        message (why #(net/on! id (fn [_] nil)))]
    (is (str/includes? message "must be a var"))
    (is (str/includes? message "hot-reloadable"))))

;;; The client half. Registering a receiver for real needs a client game, so
;;; what is checked here is the seam that a client game would drive: the proxy
;;; Skein hands to Fabric implements the client handler interface, and a payload
;;; delivered through it reaches the handler var as data. The internals are
;;; reached directly on purpose — the public path (`on!`) would try to talk to a
;;; client that does not exist in a unit-test JVM.

(def ^:private received (atom nil))

(defn- on-client-packet [handler-map] (reset! received handler-map))

(defn- fake-client-context
  "A stand-in for the context Fabric passes a client handler."
  []
  (let [ctx-class (Class/forName "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$Context"
                                 false (.getClassLoader clojure.lang.RT))]
    (java.lang.reflect.Proxy/newProxyInstance
     (.getClassLoader ctx-class)
     (into-array Class [ctx-class])
     (reify java.lang.reflect.InvocationHandler
       (invoke [_ _proxy _method _args] nil)))))

(deftest a-client-receiver-delivers-the-payload-as-data
  (let [id (net/define! (fresh-id "clientbound") {:schema [:map [:score :int]] :dir :s2c})
        receiver (#'net/client-receiver id)]
    (swap! @#'net/packets assoc-in [id :handlers :client]
           {:handler #'on-client-packet :style :fn})
    (is (some #{"PlayPayloadHandler"} (map #(.getSimpleName ^Class %) (.getInterfaces (class receiver))))
        "the receiver implements Fabric's client handler interface")
    (reset! received nil)
    (.receive receiver (net/->payload id {:score 9}) (fake-client-context))
    (is (= {:score 9} (:data @received)) "the handler saw the payload as data")
    (is (= :client (:side @received)) "and knows it was handled on the client")))

(deftest broadcasting-needs-a-server-or-a-level
  (let [id (net/define! (fresh-id "broadcast") {:schema :int :dir :s2c})]
    (is (str/includes? (why #(net/broadcast! "not a server" id 1))
                       "expected a MinecraftServer or a ServerLevel"))))
