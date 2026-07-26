(ns skein.client.hud-test
  "Unit tests for HUD-overlays-as-data: the placement schema, anchor coercion,
  and the render seam (that it derefs the current var every frame, which is what
  makes the overlay hot-reloadable). Registering with the game's HUD needs a
  running client; the seam is driven here with a real — if empty — graphics
  object and a render fn that only reads the context."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.client.hud :as hud])
  (:import (net.minecraft SharedConstants DetectedVersion)
           (net.minecraft.resources Identifier)
           (net.minecraft.server Bootstrap)))

(defonce ^:private _boot
  (do (SharedConstants/setVersion DetectedVersion/BUILT_IN)
      (Bootstrap/bootStrap)
      true))

(defn- why [f]
  (try (f) nil (catch Exception e (.getMessage e))))

;;; Validation

(deftest a-bad-add-explains-itself
  (testing "an id that is not namespace-qualified"
    (is (str/includes? (why #(hud/add! :score (fn [_]))) ":mymod/score")))
  (testing "a render fn that is not a var"
    (is (str/includes? (why #(hud/add! :mymod/score (fn [_]))) "must be a var")))
  (testing "an unknown placement option"
    (is (str/includes? (why #(hud/add! :mymod/score #'why :below :hotbar)) "[:below]"))))

;;; Anchor coercion

(deftest a-vanilla-anchor-keyword-resolves-to-an-identifier
  (is (instance? Identifier (#'hud/->anchor :hotbar)))
  (is (instance? Identifier (#'hud/->anchor :chat))))

(deftest a-namespaced-anchor-is-coerced-as-an-id
  (is (= "mymod:thing" (str (#'hud/->anchor :mymod/thing)))))

(deftest an-unknown-vanilla-anchor-is-explained
  (is (str/includes? (why #(#'hud/->anchor :nope)) "Unknown HUD anchor")))

;;; The render seam — reads the current var every frame

(def ^:private drawn (atom nil))
(defn- render-a [{:keys [width height delta]}] (reset! drawn [:a width height (some? delta)]))
(defn- render-b [{:keys [width]}] (reset! drawn [:b width]))

;; render-context builds the map from a live graphics object; a real
;; GuiGraphicsExtractor needs a running client, so stub it out and drive the
;; part under test — that dispatch! reads the current handler var every frame.
(defn- stub-context [_graphics delta]
  {:graphics :the-graphics :width 320 :height 240 :delta delta :client nil})

(deftest a-frame-runs-the-render-fn-currently-registered-and-sees-the-hud-size
  (with-redefs [hud/render-context stub-context]
    (let [id :skein_hud_test/probe]
      ;; Poke the registry directly — add! would try to talk to a client that
      ;; does not exist in a unit-test JVM.
      (swap! @#'hud/overlays assoc id {:handler #'render-a :placement nil})
      (reset! drawn nil)
      (#'hud/dispatch! id :the-graphics :the-delta)
      (is (= [:a 320 240 true] @drawn) "the render fn saw the HUD size and the delta")
      (testing "redefining the overlay changes what the next frame draws"
        (swap! @#'hud/overlays assoc-in [id :handler] #'render-b)
        (#'hud/dispatch! id :the-graphics :the-delta)
        (is (= [:b 320] @drawn))))))

(deftest registered-reports-overlays-as-data
  (swap! @#'hud/overlays assoc :skein_hud_test/shown {:handler #'render-a :placement {:after :hotbar}})
  (let [o (get (hud/registered) :skein_hud_test/shown)]
    (is (= #'render-a (:handler o)))
    (is (= {:after :hotbar} (:placement o)))))
