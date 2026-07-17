(ns skein.mixin.schema-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skein.mixin.schema :as schema]))

(def ^:private valid
  {:mixins [{:target 'net.minecraft.server.MinecraftServer
             :class 'mymod.skein_mixins.ServerMixin
             :injects [{:type :inject
                        :method 'tickServer
                        :at :head
                        :cancellable true
                        :handler 'mymod.core/on-server-tick}
                       {:type :modify-arg
                        :method 'buildServerStatus
                        :at {:value :invoke
                             :target {:owner 'net.minecraft.network.chat.Component
                                      :name 'literal}}
                        :index 0
                        :handler 'mymod.core/modify-status}
                       {:type :modify-return
                        :method {:name 'getMotd :params '[]}
                        :handler 'mymod.core/on-motd}]}]})

(defn- schema-error [data]
  (try
    (schema/validate! data "mixins.edn")
    nil
    (catch clojure.lang.ExceptionInfo e
      (.getMessage e))))

(deftest accepts-the-authored-shape
  (is (= valid (schema/validate! valid "mixins.edn"))))

(deftest rejects-an-unknown-injector-type
  (let [message (schema-error {:mixins [{:target 'a.B
                                         :injects [{:type :redirect
                                                    :method 'x
                                                    :handler 'a.b/c}]}]})]
    (is (some? message))
    (is (str/includes? message "mixins.edn"))))

(deftest rejects-an-unqualified-handler
  (is (some? (schema-error {:mixins [{:target 'a.B
                                      :injects [{:type :inject
                                                 :method 'x
                                                 :at :head
                                                 :handler 'unqualified}]}]}))))

(deftest inject-needs-at-and-modify-return-must-not-have-one
  (is (some? (schema-error {:mixins [{:target 'a.B
                                      :injects [{:type :inject
                                                 :method 'x
                                                 :handler 'a.b/c}]}]})))
  (is (some? (schema-error {:mixins [{:target 'a.B
                                      :injects [{:type :modify-return
                                                 :method 'x
                                                 :at :head
                                                 :handler 'a.b/c}]}]}))))

(deftest rejects-an-empty-injects-vector
  (is (some? (schema-error {:mixins [{:target 'a.B :injects []}]}))))
