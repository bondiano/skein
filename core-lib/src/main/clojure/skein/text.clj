(ns skein.text
  "Chat text as data — a hiccup-style grammar that coerces to a Component.

      (text \"plain\")
      (text [:red \"error: \" [:bold \"boom\"]])
      (text {:text \"hi\" :color :gold :bold true})
      (text [:translate \"block.mymod.ruby\"])
      (text {:text \"[click]\" :color :aqua
             :hover {:action :show-text :value [:italic \"spawn home\"]}
             :click {:action :run-command :value \"/spawn\"}})

  Grammar:
  - a string is a literal component;
  - a vector `[tag & children]` where `tag` is a style keyword (`:red`,
    `:bold`, `:dark-red`, ...) styles an empty parent and appends the coerced
    children (which inherit the parent's style, as Minecraft does);
  - a vector `[:translate key & args]` is a translatable component: `key` is the
    lang-file key, `args` (optional) fill its placeholders — each arg is coerced
    as text unless it is already a number/boolean, which pass through;
  - a map `{:text .. :color .. :extra [..] :hover .. :click ..}` is an explicit
    node: `:text` is its literal string, `:color` is a color keyword (`:gold`)
    or a hex string (`\"#ff8800\"`), the boolean keys `:bold :italic :underlined
    :strikethrough :obfuscated` set formatting, `:extra` is a vector of child
    nodes, and `:hover`/`:click` attach interactivity (below);
  - an existing Component passes through unchanged.

  Interactivity — a `:hover`/`:click` value is a map `{:action .. :value ..}`
  mirroring the vanilla event names:
  - `:hover {:action :show-text  :value <text form>}`
           `{:action :show-item  :value <item id | {:item :count}>}`
           `{:action :show-entity :value {:type <id> :uuid <uuid> :name <text?>}}`
  - `:click {:action :run-command     :value \"/cmd\"}`
           `{:action :suggest-command :value \"/cmd \"}`
           `{:action :open-url        :value \"https://...\"}`
           `{:action :copy-to-clipboard :value \"text\"}`
           `{:action :change-page     :value 2}`

  `component->data` is the inverse (for tests and logging); it round-trips the
  text, named/hex color, formatting flags, children, translate keys and the
  common hover/click actions."
  (:require [clojure.string :as str]
            [skein.coerce :as coerce]
            [skein.id :as id])
  (:import (java.net URI)
           (java.util Optional)
           (net.minecraft ChatFormatting)
           (net.minecraft.core.registries BuiltInRegistries)
           (net.minecraft.network.chat ClickEvent ClickEvent$ChangePage
                                       ClickEvent$CopyToClipboard ClickEvent$OpenUrl
                                       ClickEvent$RunCommand ClickEvent$SuggestCommand
                                       Component HoverEvent HoverEvent$EntityTooltipInfo
                                       HoverEvent$ShowEntity HoverEvent$ShowItem
                                       HoverEvent$ShowText MutableComponent Style TextColor)
           (net.minecraft.network.chat.contents PlainTextContents TranslatableContents)
           (net.minecraft.world.entity EntityType)
           (net.minecraft.world.item Item ItemStackTemplate)))

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

;;; Interactivity: hover/click action maps -> HoverEvent/ClickEvent

(declare text component->data)

(defn- item-by-id ^Item [item-id]
  (let [identifier (id/id item-id)
        holder (.get BuiltInRegistries/ITEM identifier)]
    (if (.isPresent holder)
      (.value ^net.minecraft.core.Holder (.get holder))
      (throw (ex-info (str "No item registered for " (pr-str item-id) " (" identifier
                           ") in a :show-item hover")
                      {:item item-id})))))

(defn- ->item-template ^ItemStackTemplate [v]
  (cond
    (or (keyword? v) (string? v)) (ItemStackTemplate. (item-by-id v))
    (map? v) (ItemStackTemplate. (item-by-id (:item v)) (int (or (:count v) 1)))
    :else (throw (ex-info (str "A :show-item hover :value must be an item id or a {:item :count} map, got: " (pr-str v))
                          {:value v}))))

(defn- entity-type-by-id ^EntityType [type-id]
  (let [identifier (id/id type-id)
        holder (.get BuiltInRegistries/ENTITY_TYPE identifier)]
    (if (.isPresent holder)
      (.value ^net.minecraft.core.Holder (.get holder))
      (throw (ex-info (str "No entity type registered for " (pr-str type-id) " (" identifier
                           ") in a :show-entity hover")
                      {:entity-type type-id})))))

(defn- ->hover-event ^HoverEvent [{:keys [action value] :as m}]
  (case action
    :show-text  (HoverEvent$ShowText. (text value))
    :show-item  (HoverEvent$ShowItem. (->item-template value))
    :show-entity (let [{:keys [type uuid name]} value]
                   (HoverEvent$ShowEntity.
                    (HoverEvent$EntityTooltipInfo.
                     (entity-type-by-id type) ^java.util.UUID uuid
                     (Optional/ofNullable (when name (text name))))))
    (throw (ex-info (str ":hover :action " (pr-str action)
                         " is not one of :show-text, :show-item, :show-entity")
                    {:node m}))))

(defn- ->click-event ^ClickEvent [{:keys [action value] :as m}]
  (case action
    :run-command      (ClickEvent$RunCommand. (str value))
    :suggest-command  (ClickEvent$SuggestCommand. (str value))
    :open-url         (ClickEvent$OpenUrl. (URI/create (str value)))
    :copy-to-clipboard (ClickEvent$CopyToClipboard. (str value))
    :change-page      (ClickEvent$ChangePage. (int value))
    (throw (ex-info (str ":click :action " (pr-str action)
                         " is not one of :run-command, :suggest-command, :open-url,"
                         " :copy-to-clipboard, :change-page")
                    {:node m}))))

(defn- action-map! [what v]
  (when-not (map? v)
    (throw (ex-info (str what " must be a {:action .. :value ..} map, got: " (pr-str v))
                    {:value v})))
  v)

(defn- build-style
  ^Style [{:keys [color bold italic underlined strikethrough obfuscated hover click] :as m}]
  (cond-> Style/EMPTY
    (some? color) (.withColor (->text-color color))
    (contains? m :bold) (.withBold (boolean bold))
    (contains? m :italic) (.withItalic (boolean italic))
    (contains? m :underlined) (.withUnderlined (boolean underlined))
    (contains? m :strikethrough) (.withStrikethrough (boolean strikethrough))
    (contains? m :obfuscated) (.withObfuscated (boolean obfuscated))
    (some? hover) (.withHoverEvent (->hover-event (action-map! ":hover" hover)))
    (some? click) (.withClickEvent (->click-event (action-map! ":click" click)))))

;;; data -> Component

(defn- translate-arg [a]
  (if (or (number? a) (boolean? a)) a (text a)))

(defn- translate->component ^MutableComponent [[_ key & args]]
  (when-not (string? key)
    (throw (ex-info (str "A [:translate key & args] needs a string lang key, got: " (pr-str key))
                    {:value key})))
  (if (seq args)
    (Component/translatable key (object-array (map translate-arg args)))
    (Component/translatable key)))

(defn- vector->component ^MutableComponent [[tag & children :as v]]
  (when-not (keyword? tag)
    (throw (ex-info (str "A text vector must start with a style keyword or :translate, got " (pr-str tag))
                    {:value tag})))
  (if (= tag :translate)
    (translate->component v)
    (let [root (.withStyle (Component/literal "") (kw->formatting tag))]
      (doseq [child children]
        (.append root ^Component (text child)))
      root)))

(defn- map->component ^MutableComponent [{:keys [extra] :as m}]
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

;; Dispatch the reverse direction on the event's Action enum name, never on a
;; class literal of the record subtypes: HoverEvent$ShowItem / ShowEntity (and
;; the ClickEvent records) carry static registry-backed codecs, and naming the
;; class as a value would run their <clinit> when this namespace compiles —
;; before the game is bootstrapped. A method call (`.action`) and a checkcast
;; type hint (which links but does not initialize) stay clear of that.

(defn- hover->data [^HoverEvent hover]
  (case (.name ^Enum (.action hover))
    "SHOW_TEXT" {:action :show-text :value (component->data (.value ^HoverEvent$ShowText hover))}
    ;; show-item / show-entity round-trip to their action tag; the payload (an
    ;; ItemStackTemplate / entity tooltip) is opaque enough that logs and tests
    ;; care about the action, not its exact contents.
    "SHOW_ITEM" {:action :show-item}
    "SHOW_ENTITY" {:action :show-entity}
    {:action :unknown}))

(defn- click->data [^ClickEvent click]
  (case (.name ^Enum (.action click))
    "RUN_COMMAND"       {:action :run-command :value (.command ^ClickEvent$RunCommand click)}
    "SUGGEST_COMMAND"   {:action :suggest-command :value (.command ^ClickEvent$SuggestCommand click)}
    "OPEN_URL"          {:action :open-url :value (str (.uri ^ClickEvent$OpenUrl click))}
    "COPY_TO_CLIPBOARD" {:action :copy-to-clipboard :value (.value ^ClickEvent$CopyToClipboard click)}
    "CHANGE_PAGE"       {:action :change-page :value (.page ^ClickEvent$ChangePage click)}
    {:action :unknown}))

(defn component->data
  "Read a Component back into the data grammar (for tests and logs).
  A plain unstyled literal returns its string; a translatable returns
  `[:translate key & args]`; anything with a color, a formatting flag, an
  interaction or children returns a map."
  [^Component component]
  (let [contents (.getContents component)
        style (.getStyle component)
        children (mapv component->data (.getSiblings component))]
    (if (instance? TranslatableContents contents)
      (let [tc ^TranslatableContents contents
            args (vec (.getArgs tc))]
        (into [:translate (.getKey tc)]
              (map (fn [a] (if (instance? Component a) (component->data a) a)) args)))
      (let [;; An empty literal is the styled container a vector node builds; elide
            ;; it so `[:red "x"]` reads back as {:color :red :extra ["x"]}.
            literal (when (instance? PlainTextContents contents)
                      (let [t (.text ^PlainTextContents contents)]
                        (when (seq t) t)))
            hover (.getHoverEvent style)
            click (.getClickEvent style)
            node (cond-> {}
                   (some? literal) (assoc :text literal)
                   (some? (.getColor style)) (assoc :color (color->data (.getColor style)))
                   (.isBold style) (assoc :bold true)
                   (.isItalic style) (assoc :italic true)
                   (.isUnderlined style) (assoc :underlined true)
                   (.isStrikethrough style) (assoc :strikethrough true)
                   (.isObfuscated style) (assoc :obfuscated true)
                   (some? hover) (assoc :hover (hover->data hover))
                   (some? click) (assoc :click (click->data click))
                   (seq children) (assoc :extra children))]
        ;; A bare literal with no styling and no children reads back as its string.
        (if (= node {:text literal})
          literal
          node)))))
