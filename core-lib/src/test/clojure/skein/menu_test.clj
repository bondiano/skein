(ns skein.menu-test
  "Unit tests for menus-as-data: building the backing container from data,
  reading its contents back, and the declaration schema. Opening the menu for a
  player and the on-close callback need a live server with a connected player
  and live in an L2 / manual check."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.menu :as menu]
            [skein.schema :as schema])
  (:import (net.minecraft SharedConstants DetectedVersion)
           (net.minecraft.server Bootstrap)))

;; Building a container and coercing item data needs the item registry
;; bootstrapped — no server or world. Idempotent; shares the test JVM.
(defonce ^:private _boot
  (do (SharedConstants/setVersion DetectedVersion/BUILT_IN)
      (Bootstrap/bootStrap)
      true))

(defn- why [s v] (schema/explain-str s v))

(deftest a-container-has-nine-slots-per-row
  (is (= 27 (.getContainerSize (menu/container {:rows 3}))))
  (is (= 54 (.getContainerSize (menu/container {:rows 6})))))

(deftest an-empty-container-reads-back-all-nils
  ;; contents over ItemStack.EMPTY needs no item bootstrap; prefilling real
  ;; stacks does (their components bind only on a live server), so the
  ;; prefill/contents round-trip lives in the L2 suite.
  (let [read (menu/contents (menu/container {:rows 1}))]
    (is (= 9 (count read)))
    (is (every? nil? read))))

(deftest a-slot-out-of-range-is-explained
  ;; The range check fires before the item is coerced, so this needs no item
  ;; bootstrap.
  (is (thrown-with-msg? Exception #"out of range"
                        (menu/container {:rows 1 :items {20 :minecraft/stone}}))))

(deftest the-menu-type-covers-one-to-six-rows
  (doseq [rows (range 1 7)]
    (is (some? (#'menu/->menu-type rows)) (str rows " rows")))
  (testing "seven rows is not a chest"
    (is (str/includes? (try (#'menu/->menu-type 7) (catch Exception e (.getMessage e)))
                       "1–6 rows"))))

(deftest the-declaration-schema-guards-the-boundary
  (testing "rows must be 1–6"
    (is (some? (why menu/chest {:title "x" :rows 0})))
    (is (some? (why menu/chest {:title "x" :rows 7}))))
  (testing "an on-close that is not a var"
    (is (str/includes? (why menu/chest {:title "x" :rows 3 :on-close (fn [_])})
                       "must be a var")))
  (testing "an unknown key is caught (closed map)"
    (is (str/includes? (why menu/chest {:title "x" :rows 3 :bogus 1}) "[:bogus]"))))
