(ns skein.client.draw
  "Data-friendly drawing helpers over the client's graphics object — the small
  surface a HUD overlay or a screen reaches for.

  A HUD render fn receives a `:graphics` object (the game's
  `GuiGraphicsExtractor`); these helpers draw onto it while keeping the data on
  the boundary: text is skein.text hiccup, an item is an id, a colour is a
  keyword / \"#RRGGBB\" string / raw ARGB int.

      (defn render [{:keys [graphics width]}]
        (draw/text! graphics [:gold \"Score: 42\"] 4 4)
        (draw/item! graphics :minecraft/diamond (- width 20) 4))

  Colours are ARGB integers. A keyword names one of a small palette (:white
  :black :red :green :blue :yellow :gray :aqua :gold :dark-gray, plus
  :transparent); a \"#RRGGBB\" string is opaque, \"#AARRGGBB\" carries its own
  alpha; an int is used as-is. `text!` defaults to :white.

  Client-only, like the rest of skein.client.*."
  (:require [skein.item :as item]
            [skein.text :as text])
  (:import (net.minecraft.client Minecraft)
           (net.minecraft.client.gui Font GuiGraphicsExtractor)
           (net.minecraft.network.chat Component)
           (net.minecraft.world.item ItemStack)))

(def ^:private palette
  {:transparent 0x00000000
   :white       (unchecked-int 0xFFFFFFFF)
   :black       (unchecked-int 0xFF000000)
   :dark-gray   (unchecked-int 0xFF555555)
   :gray        (unchecked-int 0xFFAAAAAA)
   :red         (unchecked-int 0xFFFF5555)
   :green       (unchecked-int 0xFF55FF55)
   :blue        (unchecked-int 0xFF5555FF)
   :yellow      (unchecked-int 0xFFFFFF55)
   :aqua        (unchecked-int 0xFF55FFFF)
   :gold        (unchecked-int 0xFFFFAA00)})

(defn color
  "Coerce a colour value to an ARGB int: a palette keyword, a \"#RRGGBB\" /
  \"#AARRGGBB\" string, or an int (passed through). A bare \"#RRGGBB\" is opaque."
  ^long [c]
  (cond
    (integer? c) (unchecked-int c)
    (keyword? c) (or (palette c)
                     (throw (ex-info (str "Unknown colour " c ". Use one of "
                                          (vec (sort (keys palette)))
                                          ", a \"#RRGGBB\" string, or a raw ARGB int.")
                                     {:color c})))
    (string? c) (let [s (if (= \# (first c)) (subs c 1) c)
                      n (Long/parseLong s 16)]
                  (unchecked-int (if (<= (count s) 6) (bit-or 0xFF000000 n) n)))
    :else (throw (ex-info (str "Cannot read a colour from " (pr-str c)
                               " — expected a keyword, a \"#RRGGBB\" string, or an ARGB int.")
                          {:color c}))))

(defn font
  "The client's default font."
  ^Font []
  (.-font (Minecraft/getInstance)))

(defn text-width
  "The pixel width the given text (hiccup / string / Component) would take in the
  default font — for laying out an overlay against the screen edge."
  ^long [content]
  (.width (font) ^Component (text/text content)))

(defn text!
  "Draws text at (x, y). `content` is skein.text hiccup (a string, a vector, a
  Component); `col` is any colour value and defaults to :white. Drawn with a
  drop shadow, as vanilla HUD text is."
  ([^GuiGraphicsExtractor graphics content x y] (text! graphics content x y :white))
  ([^GuiGraphicsExtractor graphics content x y col]
   (.text graphics (font) ^Component (text/text content) (int x) (int y) (int (color col)) true)
   graphics))

(defn centered-text!
  "Like `text!`, but horizontally centred on x."
  ([^GuiGraphicsExtractor graphics content x y] (centered-text! graphics content x y :white))
  ([^GuiGraphicsExtractor graphics content x y col]
   (.centeredText graphics (font) ^Component (text/text content) (int x) (int y) (int (color col)))
   graphics))

(defn fill!
  "Fills the rectangle from (x1, y1) to (x2, y2) with a colour — e.g. a
  translucent panel behind an overlay."
  [^GuiGraphicsExtractor graphics x1 y1 x2 y2 col]
  (.fill graphics (int x1) (int y1) (int x2) (int y2) (int (color col)))
  graphics)

(defn item!
  "Draws an item icon at (x, y). `item` is an id (`:minecraft/diamond`), an
  item-map (`{:item :mymod/ruby :count 3}`) or a ready ItemStack; a count > 1 is
  shown as vanilla draws it."
  [^GuiGraphicsExtractor graphics item x y]
  (let [^ItemStack stack (item/->stack item)]
    (.item graphics stack (int x) (int y))
    (.itemDecorations graphics (font) stack (int x) (int y)))
  graphics)
