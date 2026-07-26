(ns skein.client.draw-test
  "Unit tests for the draw layer's data boundary — colour coercion. The actual
  drawing calls need a live graphics object and font (a running client) and are
  exercised through the HUD demo."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.client.draw :as draw]))

(defn- why [f]
  (try (f) nil (catch Exception e (.getMessage e))))

(deftest a-palette-keyword-becomes-argb
  (is (= (unchecked-int 0xFFFFFFFF) (draw/color :white)))
  (is (= (unchecked-int 0xFFFF5555) (draw/color :red)))
  (is (= 0 (draw/color :transparent))))

(deftest a-hex-string-carries-or-defaults-its-alpha
  (testing "#RRGGBB is opaque, #AARRGGBB keeps its own alpha"
    (is (= (unchecked-int 0xFFAABBCC) (draw/color "#AABBCC")))
    (is (= (unchecked-int 0x10203040) (draw/color "#10203040"))))
  (testing "a leading # is optional"
    (is (= (unchecked-int 0xFFAABBCC) (draw/color "AABBCC")))))

(deftest an-int-passes-through
  (is (= (unchecked-int 0x80FF0000) (draw/color (unchecked-int 0x80FF0000)))))

(deftest an-unknown-colour-is-explained
  (is (str/includes? (why #(draw/color :chartreuse)) "Unknown colour")))
