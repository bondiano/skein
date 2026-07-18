(ns skein.pos
  "Positions as plain `[x y z]` vectors, with pure math over them.

      (above [10 64 10])   ;=> [10 65 10]
      (north [10 64 10] 3) ;=> [10 64 7]
      (pos+ [1 0 0] [0 1 0]) ;=> [1 1 0]
      (direction :north)   ;=> the Direction enum value

  All arithmetic stays on Clojure vectors — no game types are touched until
  `->blockpos` / `->vec3` at the boundary. Axes follow Minecraft's convention:
  +x east, -x west, +y up, -y down, +z south, -z north."
  (:require [skein.coerce :as coerce])
  (:import (net.minecraft.core BlockPos Direction)
           (net.minecraft.world.phys Vec3)))

;;; Pure vector math

(defn pos+
  "Component-wise sum of two position vectors."
  [[ax ay az] [bx by bz]]
  [(+ ax bx) (+ ay by) (+ az bz)])

(defn pos-
  "Component-wise difference a - b."
  [[ax ay az] [bx by bz]]
  [(- ax bx) (- ay by) (- az bz)])

(defn pos*
  "Scale a position vector by k."
  [[x y z] k]
  [(* x k) (* y k) (* z k)])

(defn above ([p] (above p 1)) ([[x y z] n] [x (+ y n) z]))
(defn below ([p] (below p 1)) ([[x y z] n] [x (- y n) z]))
(defn up    ([p] (above p 1)) ([p n] (above p n)))
(defn down  ([p] (below p 1)) ([p n] (below p n)))
(defn north ([p] (north p 1)) ([[x y z] n] [x y (- z n)]))
(defn south ([p] (south p 1)) ([[x y z] n] [x y (+ z n)]))
(defn east  ([p] (east p 1))  ([[x y z] n] [(+ x n) y z]))
(defn west  ([p] (west p 1))  ([[x y z] n] [(- x n) y z]))

;;; Directions: keyword <-> Direction enum

(def ^:private direction-vectors
  {:north [0 0 -1] :south [0 0 1]
   :east  [1 0 0]  :west  [-1 0 0]
   :up    [0 1 0]  :down  [0 -1 0]})

(defn direction-vector
  "The unit `[x y z]` step for a direction keyword, or nil if unknown."
  [dir-kw]
  (direction-vectors dir-kw))

(defn direction
  "Coerce a direction keyword (`:north` ... `:down`) to a Direction enum value."
  ^Direction [dir-kw]
  (or (Direction/byName (name dir-kw))
      (throw (ex-info (str "Unknown direction " (pr-str dir-kw)
                           " — expected one of " (vec (sort (keys direction-vectors))))
                      {:value dir-kw}))))

(defn direction->kw
  "The keyword for a Direction enum value: the inverse of `direction`."
  [^Direction dir]
  (keyword (.getName dir)))

;;; Boundary coercion

(defn- floor-int ^long [n]
  (long (Math/floor (double n))))

(defn ->blockpos
  "Coerce to a BlockPos. Shorthand for `coerce/->blockpos`."
  ^BlockPos [x]
  (coerce/->blockpos x))

(defn ->vec3
  "Coerce to a Vec3. Shorthand for `coerce/->vec3`."
  ^Vec3 [x]
  (coerce/->vec3 x))

(defn blockpos->
  "The `[x y z]` vector of a BlockPos: the inverse of `->blockpos`."
  [^BlockPos p]
  [(.getX p) (.getY p) (.getZ p)])

(defn vec3->
  "The `[x y z]` vector of a Vec3: the inverse of `->vec3`."
  [^Vec3 v]
  [(.x v) (.y v) (.z v)])

(extend-protocol coerce/Pos
  clojure.lang.IPersistentVector
  (->blockpos [[x y z]] (BlockPos. (int (floor-int x)) (int (floor-int y)) (int (floor-int z))))
  (->vec3 [[x y z]] (Vec3. (double x) (double y) (double z)))

  BlockPos
  (->blockpos [p] p)
  (->vec3 [p] (Vec3. (double (.getX p)) (double (.getY p)) (double (.getZ p))))

  Vec3
  (->blockpos [v] (BlockPos. (int (floor-int (.x v))) (int (floor-int (.y v))) (int (floor-int (.z v)))))
  (->vec3 [v] v))
