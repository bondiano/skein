(ns skein.pos-test
  (:require [clojure.test :refer [deftest is]]
            [skein.pos :as pos])
  (:import (net.minecraft.core BlockPos Direction)
           (net.minecraft.world.phys Vec3)))

(deftest vector-math-is-pure
  (is (= [10 65 10] (pos/above [10 64 10])))
  (is (= [10 62 10] (pos/below [10 64 10] 2)))
  (is (= [10 64 7] (pos/north [10 64 10] 3)))
  (is (= [10 64 13] (pos/south [10 64 10] 3)))
  (is (= [13 64 10] (pos/east [10 64 10] 3)))
  (is (= [7 64 10] (pos/west [10 64 10] 3)))
  (is (= [1 1 0] (pos/pos+ [1 0 0] [0 1 0])))
  (is (= [1 -1 0] (pos/pos- [1 0 0] [0 1 0])))
  (is (= [2 4 6] (pos/pos* [1 2 3] 2))))

(deftest directions-round-trip
  (doseq [d [:north :south :east :west :up :down]]
    (is (= d (pos/direction->kw (pos/direction d)))))
  (is (instance? Direction (pos/direction :north)))
  (is (= [0 0 -1] (pos/direction-vector :north)))
  (is (= [0 -1 0] (pos/direction-vector :down))))

(deftest direction-rejects-unknown
  (is (thrown? clojure.lang.ExceptionInfo (pos/direction :sideways))))

(deftest coerces-to-blockpos
  (let [bp (pos/->blockpos [10 64 10])]
    (is (instance? BlockPos bp))
    (is (= [10 64 10] [(.getX bp) (.getY bp) (.getZ bp)]))
    (is (= [10 64 10] (pos/blockpos-> bp))))
  ;; a fractional entity position floors to its block
  (is (= [10 64 -3] (pos/blockpos-> (pos/->blockpos [10.9 64.1 -2.1])))))

(deftest coerces-to-vec3
  (let [v (pos/->vec3 [10.5 64.0 10.5])]
    (is (instance? Vec3 v))
    (is (= [10.5 64.0 10.5] (pos/vec3-> v))))
  (let [v (Vec3. 1.0 2.0 3.0)]
    (is (identical? v (pos/->vec3 v)))))
