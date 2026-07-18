(ns skein.text
  "Chat text as data — a hiccup-style grammar that coerces to a Component.

      (text \"plain\")
      (text [:red \"error: \" [:bold \"boom\"]])
      (text {:text \"hi\" :color :gold :bold true})

  Grammar:
  - a string is a literal component;
  - a vector `[tag & children]` where `tag` is a style keyword (`:red`,
    `:bold`, `:dark-red`, ...) styles an empty parent and appends the coerced
    children (which inherit the parent's style, as Minecraft does);
  - a map `{:text .. :color .. :bold .. :extra [..]}` is an explicit node:
    `:text` is its literal string, `:color` is a color keyword (`:gold`) or a
    hex string (`\"#ff8800\"`), the boolean keys `:bold :italic :underlined
    :strikethrough :obfuscated` set formatting, and `:extra` is a vector of
    child nodes;
  - an existing Component passes through unchanged.

  `component->data` is the inverse (for tests and logging); it round-trips the
  text, named/hex color, formatting flags and children."
  (:require [clojure.string :as str]
            [skein.coerce :as coerce])
  (:import (net.minecraft ChatFormatting)
           (net.minecraft.network.chat Component MutableComponent Style TextColor)
           (net.minecraft.network.chat.contents PlainTextContents)))

;;; Colors and formatting keywords

(defn- kw->formatting
  "A style keyword (`:red`, `:dark-red`, `:bold`) to its ChatFormatting."
  ^ChatFormatting [k]
  (let [enum-name (-> (name k) (str/replace "-" "_") str/upper-case)]
    (try
      (ChatFormatting/valueOf enum-name)
      (catch IllegalArgumentException _
        (throw (ex-info (str "Unknown style keyword " k
                             " — expected a color or formatting name like :red, :gold or :bold")
                        {:value k}))))))

(defn- ->text-color ^TextColor [color]
  (cond
    (keyword? color) (TextColor/fromLegacyFormat (kw->formatting color))
    (string? color) (TextColor/fromRgb (Integer/parseInt (subs color 1) 16))
    (integer? color) (TextColor/fromRgb (int color))
    :else (throw (ex-info (str "Cannot read a color from " (pr-str color)
                               " — expected a color keyword (:gold) or a hex string (\"#ff8800\")")
                          {:value color}))))

(defn- build-style
  ^Style [{:keys [color bold italic underlined strikethrough obfuscated] :as m}]
  (cond-> Style/EMPTY
    (some? color) (.withColor (->text-color color))
    (contains? m :bold) (.withBold (boolean bold))
    (contains? m :italic) (.withItalic (boolean italic))
    (contains? m :underlined) (.withUnderlined (boolean underlined))
    (contains? m :strikethrough) (.withStrikethrough (boolean strikethrough))
    (contains? m :obfuscated) (.withObfuscated (boolean obfuscated))))

;;; data -> Component

(declare text)

(defn- vector->component ^MutableComponent [[tag & children]]
  (when-not (keyword? tag)
    (throw (ex-info (str "A text vector must start with a style keyword, got " (pr-str tag))
                    {:value tag})))
  (let [root (.withStyle (Component/literal "") (kw->formatting tag))]
    (doseq [child children]
      (.append root ^Component (text child)))
    root))

(defn- map->component ^MutableComponent [{:keys [extra hover click] :as m}]
  (when (or hover click)
    (throw (ex-info "Hover and click actions are not implemented yet in skein.text"
                    {:node m})))
  (let [root (.setStyle (Component/literal (or (:text m) "")) (build-style m))]
    (doseq [child extra]
      (.append root ^Component (text child)))
    root))

(defn text
  "Coerce x (string / vector / map / Component) to a Component.
  Shorthand for `coerce/->component`."
  ^Component [x]
  (coerce/->component x))

(extend-protocol coerce/Text
  String
  (->component [s] (Component/literal s))

  clojure.lang.IPersistentVector
  (->component [v] (vector->component v))

  clojure.lang.IPersistentMap
  (->component [m] (map->component m))

  Component
  (->component [c] c))

;;; Component -> data

(defn- color->data [^TextColor color]
  (let [s (.serialize color)]
    (if (str/starts-with? s "#") s (keyword s))))

(defn component->data
  "Read a Component back into the data grammar (for tests and logs).
  A plain unstyled literal returns its string; anything with a color, a
  formatting flag or children returns a map `{:text .. :color .. :extra ..}`."
  [^Component component]
  (let [contents (.getContents component)
        ;; An empty literal is the styled container a vector node builds; elide
        ;; it so `[:red "x"]` reads back as {:color :red :extra ["x"]}.
        literal (when (instance? PlainTextContents contents)
                  (let [t (.text ^PlainTextContents contents)]
                    (when (seq t) t)))
        style (.getStyle component)
        children (mapv component->data (.getSiblings component))
        node (cond-> {}
               (some? literal) (assoc :text literal)
               (some? (.getColor style)) (assoc :color (color->data (.getColor style)))
               (.isBold style) (assoc :bold true)
               (.isItalic style) (assoc :italic true)
               (.isUnderlined style) (assoc :underlined true)
               (.isStrikethrough style) (assoc :strikethrough true)
               (.isObfuscated style) (assoc :obfuscated true)
               (seq children) (assoc :extra children))]
    ;; A bare literal with no styling and no children reads back as its string.
    (if (= node {:text literal})
      literal
      node)))
