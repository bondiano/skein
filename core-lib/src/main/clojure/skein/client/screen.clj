(ns skein.client.screen
  "Simple screens as data: a menu of buttons and labels described by a map, its
  callbacks vars.

      (screen/open!
        {:title \"Ruby Menu\"
         :labels  [{:text [:gold \"Choose:\"] :x 20 :y 30}]
         :widgets [[:button {:text \"Give me a ruby\" :x 20 :y 50 :width 160
                             :on-click #'give}]
                   [:button {:text \"Close\" :x 20 :y 76 :width 160
                             :on-click #'close}]]})

      (defn give  [{:keys [client]}] ...)         ; a button was pressed
      (defn close [_ctx] (screen/close!))

  A screen is a map:

      {:title  <text>                 ; hiccup / string / Component
       :widgets [[:button {...}] ...]  ; interactive widgets (buttons)
       :labels  [{:text ... :x ... :y ... :color ...} ...]  ; static text
       :render  #'draw-more            ; optional: freeform drawing, a var
       :on-close #'on-close}           ; optional: called when the screen closes

  A `:button` is `{:text <text> :x :y :width? :height? :on-click #'handler
  :tooltip? <text>}`; its handler is a var of a context map
  `{:client <Minecraft> :screen <Screen>}`, so redefining it changes what the
  button does the next time it is pressed. `:render` (drawn on top, after the
  widgets) is a var of the same render map skein.client.hud passes, plus
  `:mouse-x`/`:mouse-y`; redefine it and the drawing changes on the next frame.
  The layout itself (which widgets exist, where) is fixed for one opening — call
  `open!` again to change it.

  Client-only: open from client code (an event, a keybinding, a packet handler).
  Everything here loads anywhere; open!/close! raise an actionable error on a
  dedicated server."
  (:require [skein.client.draw :as draw]
            [skein.interop :as interop]
            [skein.schema :as schema]
            [skein.text :as text])
  (:import (net.minecraft.client Minecraft)
           (net.minecraft.client.gui.components AbstractWidget Button Button$OnPress Tooltip)
           (net.minecraft.client.gui.screens Screen)))

;;; Schema — the boundary for a screen described as data

(def ^:private a-var
  [:fn {:error/message "must be a var like #'handler (so it hot-reloads)"} var?])

(def button-widget
  [:tuple [:= :button]
   [:map {:closed true}
    [:text :any]
    [:x :int] [:y :int]
    [:width {:optional true} :int]
    [:height {:optional true} :int]
    [:on-click a-var]
    [:tooltip {:optional true} :any]]])

(def label
  [:map {:closed true}
   [:text :any]
   [:x :int] [:y :int]
   [:color {:optional true} :any]])

(def screen
  "A screen described as data."
  [:map {:closed true}
   [:title :any]
   [:widgets {:optional true} [:sequential [:multi {:dispatch first}
                                            [:button button-widget]]]]
   [:labels {:optional true} [:sequential label]]
   [:render {:optional true} a-var]
   [:on-close {:optional true} a-var]])

;;; Building game widgets from the data

(defn- on-press-callback
  "The Button callback: it derefs the current on-click var and calls it with the
  context — so redefining the handler takes effect on the next press."
  ^Button$OnPress [on-click ctx-fn]
  (reify Button$OnPress
    (onPress [_ _button] (on-click (ctx-fn)))))

(defn- build-button
  ^Button [{:keys [text x y width height on-click tooltip]} ctx-fn]
  (let [b (-> (Button/builder (text/text text) (on-press-callback on-click ctx-fn))
              (.bounds (int x) (int y) (int (or width 150)) (int (or height 20)))
              (.build))]
    (when tooltip
      (.setTooltip ^AbstractWidget b (Tooltip/create (text/text tooltip))))
    b))

(defn- build-widget [[kind opts] ctx-fn]
  (case kind
    :button (build-button opts ctx-fn)
    (throw (ex-info (str "Unknown screen widget " (pr-str kind) ". Supported: :button.")
                    {:widget kind}))))

;;; The Screen object — a proxy that owns its widget list, routes input to it
;;; through children(), and renders it in the extract pipeline. No client class
;;; is touched until it is built, so this fn is safe to load anywhere.

(defn ->screen
  "Builds the game Screen object for a screen map (validated). `open!` calls this
  for you; reach for it when a game API wants a `Screen` directly (a previous
  screen's parent, say)."
  ^Screen [{:keys [title widgets labels render on-close] :as data}]
  (schema/validate! screen data "screen")
  (let [;; The interactive widgets, built lazily in init() (which is when the
        ;; screen has its size); the game calls init() before it renders.
        built (java.util.ArrayList.)]
    (proxy [Screen] [(text/text title)]
      (init []
        (.clear built)
        (let [^Screen this this
              ctx-fn (fn [] {:client (Minecraft/getInstance) :screen this})]
          (doseq [w widgets]
            (.add built (build-widget w ctx-fn)))))
      (children [] built)
      (extractRenderState [g mouse-x mouse-y delta]
        (let [^Screen this this
              ^net.minecraft.client.gui.GuiGraphicsExtractor g g]
          (.extractBackground this g (int mouse-x) (int mouse-y) (float delta))
          (doseq [^AbstractWidget w built]
            (.extractRenderState w g (int mouse-x) (int mouse-y) (float delta)))
          (doseq [{:keys [text x y color]} labels]
            (draw/text! g text x y (or color :white)))
          (when render
            (render {:graphics g
                     :width (.-width this) :height (.-height this)
                     :mouse-x mouse-x :mouse-y mouse-y
                     :delta delta :client (Minecraft/getInstance)}))))
      (onClose []
        (when on-close (on-close {:client (Minecraft/getInstance)}))
        (proxy-super onClose)))))

;;; Opening and closing

(defn open!
  "Opens a screen (a screen map, or a ready Screen). Client side."
  [screen-or-data]
  (interop/ensure-client! "Opening a screen")
  (let [^Screen s (if (instance? Screen screen-or-data) screen-or-data (->screen screen-or-data))]
    (.setScreenAndShow (Minecraft/getInstance) s)
    s))

(defn close!
  "Closes the current screen (returns to the game). Client side."
  []
  (interop/ensure-client! "Closing a screen")
  (.setScreenAndShow (Minecraft/getInstance) nil)
  nil)
