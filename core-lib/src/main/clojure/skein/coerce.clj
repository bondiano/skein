(ns skein.coerce
  "Coercion protocols — the seam between Clojure data and the live game types.

  The whole FP layer keeps data at the boundaries and objects only in the
  middle: a mod's logic works with maps, keywords and vectors; the game's
  Identifier / BlockPos / Vec3 / Component appear only where these protocols
  turn data into an object. They are open on purpose — a mod extends any of
  them for its own types with a plain `extend-protocol`, and every namespace
  that consumes a coordinate or a piece of text goes through the same seam.

  The forward direction (data -> object) lives here; the reverse (object ->
  data, for reading a snapshot back) lives in the domain namespaces next to
  the type it reads (`skein.id/id->kw`, `skein.text/component->data`, ...).")

(defprotocol Id
  "Coerce to a game Identifier (`net.minecraft.resources.Identifier`)."
  (->id [x] "Return the Identifier for x."))

(defprotocol Pos
  "Coerce to a block or entity position. A bare `[x y z]` vector is the
  Clojure-side currency; the object forms are produced only here."
  (->blockpos [x] "Return a BlockPos (integer coordinates).")
  (->vec3 [x] "Return a Vec3 (double coordinates)."))

(defprotocol Text
  "Coerce to a chat Component from a string, a hiccup vector or a map."
  (->component [x] "Return a Component."))

(defprotocol Stack
  "Coerce to an ItemStack from an item id keyword or a data map (see
  `skein.item`). Implemented in `skein.item`, open for a mod's own types."
  (->stack [x] "Return an ItemStack."))
