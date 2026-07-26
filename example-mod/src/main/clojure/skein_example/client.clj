(ns skein-example.client
  "The client half of the demo. It receives the tally packet the server sends
  when someone taps the ruby block, shows the tally as a HUD overlay, and binds
  a key that announces it — all as data, all behind vars, so redefining any of
  them from the REPL changes a connected client at once.

  The packet handler, the HUD render fn and the key handler are ordinary
  functions of a map. Nothing here touches a client class at load, so the
  namespace compiles anywhere; the client entrypoint in fabric.mod.json is what
  keeps it — and the client surfaces it drives — off a dedicated server."
  (:require [skein-example.core :as core]
            [skein.client.draw :as draw]
            [skein.client.hud :as hud]
            [skein.client.keys :as keys]
            [skein.client.screen :as screen]
            [skein.log :as log]
            [skein.net :as net]))

;; The last tally the server sent us, kept client-side so the HUD can draw it.
;; A plain atom: it is display state, not something the world saves.
(def last-taps (atom nil))

(defn on-taps
  "A tally update arrived from the server: remember it and log it."
  [{:keys [data]}]
  (reset! last-taps data)
  (log/info "ruby taps —" (:mine data) "by you," (:world data) "in this world"))

(defn render-taps
  "The HUD overlay: the ruby tally in the top-left, or nothing until the first
  tap. A pure function of the render context (see skein.client.hud) — redefine
  it from the REPL and the overlay changes on the next frame."
  [{:keys [graphics]}]
  (when-some [{:keys [mine world]} @last-taps]
    (draw/item! graphics core/ruby-item 4 4)
    (draw/text! graphics [:gold "Ruby taps: " [:white (str mine)] [:gray " / " world]] 24 8)))

(defn close-taps
  "A button on the tally screen: close it. A var, so it hot-reloads."
  [_ctx]
  (screen/close!))

(defn open-taps
  "The keybinding handler: open a small screen showing the tally, with a Close
  button. A function of the press map — redefine it and the key does something
  else. The screen is described as data; its button callback is a var."
  [_press]
  (let [line (if-some [{:keys [mine world]} @last-taps]
               [:gold (str "Tapped " mine "× by you, " world "× in this world")]
               [:gray "No ruby taps yet — right-click the ruby block."])]
    (screen/open!
     {:title [:aqua "Ruby Taps"]
      :labels [{:text line :x 24 :y 40}]
      :widgets [[:button {:text "Close" :x 24 :y 70 :width 160 :on-click #'close-taps}]]})))

(def taps-key
  "The id of the keybinding that opens the tally screen."
  :skein_example/say_taps)

(def taps-overlay
  "The id of the HUD overlay that shows the tally."
  :skein_example/taps_hud)

(defn init
  "The Fabric `client` entrypoint (see fabric.mod.json)."
  []
  (core/declare-packets!)
  (net/on! core/taps-packet #'on-taps)

  ;; A keybinding as data, its handler a hot-reloadable var.
  (keys/define! taps-key {:name "key.skein_example.say_taps"
                          :key "key.keyboard.j"
                          :category :misc})
  (keys/on! taps-key #'open-taps)

  ;; A HUD overlay as data, its render fn a hot-reloadable var.
  (hud/add! taps-overlay #'render-taps))
