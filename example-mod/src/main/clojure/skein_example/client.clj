(ns skein-example.client
  "The client half of the demo: it receives the tally packet the server sends
  when someone taps the ruby block.

  The handler is an ordinary function of a map — the payload arrives as data
  (`{:mine 3 :world 12}`), already decoded through the packet's schema — and it
  sits behind a var, so redefining it from the REPL changes what a connected
  client does with the next packet. Nothing here touches a client class, so the
  namespace compiles and loads anywhere; the client entrypoint in
  fabric.mod.json is what keeps it off a dedicated server."
  (:require [skein-example.core :as core]
            [skein.log :as log]
            [skein.net :as net]))

(defn on-taps
  "A tally update arrived from the server."
  [{:keys [data]}]
  (log/info "ruby taps —" (:mine data) "by you," (:world data) "in this world"))

(defn init
  "The Fabric `client` entrypoint (see fabric.mod.json)."
  []
  (core/declare-packets!)
  (net/on! core/taps-packet #'on-taps))
