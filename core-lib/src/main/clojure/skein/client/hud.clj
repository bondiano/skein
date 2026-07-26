(ns skein.client.hud
  "HUD overlays as data: an overlay is a render fn behind a var, so redefining it
  from the REPL changes what is drawn on the next frame — the layer's main
  visual hot-reload.

      ;; from the client entrypoint
      (hud/add! :mymod/score #'render-score)

      (defn render-score [{:keys [graphics width]}]
        (draw/text! graphics [:gold \"Score: 42\"] 4 4))

  The render fn receives one map:

      {:graphics <GuiGraphicsExtractor>  ; draw onto it — see skein.client.draw
       :width  <int> :height <int>       ; the HUD's size in gui pixels
       :delta  <DeltaTracker>            ; frame timing
       :client <Minecraft>}

  Draw with skein.client.draw (`text!`/`fill!`/`item!`), which keep the data on
  the boundary (text is hiccup, an item is an id, a colour is a keyword).

  Placement (an option to `add!`): by default the overlay draws on top of the
  whole HUD (`addLast`). `:first true` puts it underneath everything; `:after`
  or `:before` a vanilla anchor (a keyword like :hotbar / :chat / :crosshair, or
  a raw Identifier) slots it next to that element.

  Hot reload: `add!` registers the overlay with the game once and stores the
  var; the registered element derefs the current var every frame, so redefining
  the render fn — or calling `add!` again with another var — takes effect at
  once. `remove!` takes it off the HUD.

  Client-only: register from the mod's client entrypoint. Needs fabric-api (the
  rendering module) on the classpath."
  (:require [skein.id :as id]
            [skein.interop :as interop]
            [skein.schema :as schema])
  (:import (net.minecraft.client Minecraft)
           (net.minecraft.client.gui GuiGraphicsExtractor)
           (net.minecraft.resources Identifier)))

(def placement
  "Where an overlay sits in the HUD draw order."
  [:map {:closed true}
   [:first {:optional true} :boolean]
   [:after {:optional true} :any]
   [:before {:optional true} :any]])

(defonce ^:private overlays
  ;; id -> {:handler var :placement opts}. A defonce registry: the HUD element
  ;; is registered with the game once per id, and add! only swaps the var it
  ;; reads. Its keys stay in insertion order so `registered` reads cleanly.
  (atom {}))

;;; Vanilla anchors — mapped to the game's fields lazily, so nothing here
;;; resolves a client class until an anchor is actually used.

(def ^:private vanilla-anchors
  {:hotbar      (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/HOTBAR)
   :chat        (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/CHAT)
   :crosshair   (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/CROSSHAIR)
   :health-bar  (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/HEALTH_BAR)
   :food-bar    (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/FOOD_BAR)
   :armor-bar   (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/ARMOR_BAR)
   :experience  (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/EXPERIENCE_LEVEL)
   :boss-bar    (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/BOSS_BAR)
   :scoreboard  (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/SCOREBOARD)
   :player-list (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/PLAYER_LIST)
   :title       (fn [] net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements/TITLE_AND_SUBTITLE)})

(defn- ->anchor
  "The Identifier of a vanilla anchor: a keyword names one of the common HUD
  elements, or an id keyword / string / Identifier is coerced directly."
  ^Identifier [anchor]
  (cond
    (instance? Identifier anchor) anchor
    (and (keyword? anchor) (not (namespace anchor)) (vanilla-anchors anchor))
    ((vanilla-anchors anchor))
    (and (keyword? anchor) (not (namespace anchor)))
    (throw (ex-info (str "Unknown HUD anchor " anchor ". Use one of "
                         (vec (sort (keys vanilla-anchors)))
                         ", or a namespaced id / Identifier for another mod's element.")
                    {:anchor anchor}))
    :else (id/id anchor)))

;;; The render dispatch — one HudElement per overlay, reads the var every frame

(defn- render-context
  [^GuiGraphicsExtractor graphics delta]
  {:graphics graphics
   :width (.guiWidth graphics)
   :height (.guiHeight graphics)
   :delta delta
   :client (Minecraft/getInstance)})

(defn- dispatch!
  "Draws the overlay whose handler var is currently registered for id — looked
  up every frame, which is what makes it hot-reloadable."
  [id ^GuiGraphicsExtractor graphics delta]
  (when-some [handler (get-in @overlays [id :handler])]
    (handler (render-context graphics delta))))

(defn- element
  "The Fabric HudElement registered once per overlay; it reads the current var
  on every frame."
  [id]
  (reify net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
    (extractRenderState [_ graphics delta] (dispatch! id graphics delta))))

(defn- install!
  "Registers the HudElement with the game at the requested placement."
  [id ^Identifier element-id {:keys [first after before]}]
  (let [el (element id)]
    (cond
      after  (net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry/attachElementAfter
              (->anchor after) element-id el)
      before (net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry/attachElementBefore
              (->anchor before) element-id el)
      first  (net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry/addFirst element-id el)
      :else  (net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry/addLast element-id el))))

;;; Public API

(defn- ensure-fabric-api! [id]
  (try
    (Class/forName "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry"
                   false (.getClassLoader clojure.lang.RT))
    (catch ClassNotFoundException e
      (throw (ex-info (str "Cannot add the HUD overlay " id
                           ": skein.client.hud wraps the Fabric HUD API, but fabric-api is not on the"
                           " classpath. Add it to the mod's dependencies.")
                      {:overlay id} e)))))

(defn add!
  "Adds a HUD overlay: `render-var` is a var of one argument (the render map,
  see the ns docstring). `id` is a namespace-qualified keyword. Returns id.

  Options place the overlay in the draw order: `:first true`, or `:after` /
  `:before` a vanilla anchor. Adding again with another var replaces the render
  fn without re-registering — the element derefs the current var each frame, so
  the change is live."
  [id render-var & {:as opts}]
  (when-not (and (keyword? id) (namespace id))
    (throw (ex-info (str "A HUD overlay id must be a namespace-qualified keyword like :mymod/score, got: "
                         (pr-str id))
                    {:overlay id})))
  (when-not (var? render-var)
    (throw (ex-info (str "The render fn for " id " must be a var (#'render), got: " (pr-str render-var)
                         ". Registering the var — not the fn — is what makes the overlay hot-reloadable.")
                    {:overlay id :render render-var})))
  (schema/validate! placement (or opts {}) "HUD overlay placement")
  (interop/ensure-client! (str "The HUD overlay " id))
  (ensure-fabric-api! id)
  (if (contains? @overlays id)
    (swap! overlays assoc-in [id :handler] render-var)
    (do
      (swap! overlays assoc id {:handler render-var :placement opts})
      (install! id (id/id id) opts)))
  id)

(defn remove!
  "Takes the overlay off the HUD. Idempotent."
  [id]
  (when (contains? @overlays id)
    (interop/ensure-client! (str "The HUD overlay " id))
    (net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry/removeElement (id/id id))
    (swap! overlays dissoc id))
  id)

(defn registered
  "The overlays currently on the HUD, as data: id -> {:handler var :placement
  opts} (for the REPL)."
  []
  (into (sorted-map)
        (map (fn [[id {:keys [handler placement]}]] [id {:handler handler :placement placement}]))
        @overlays))
