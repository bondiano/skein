(ns skein.mixin.schema
  "Malli schema for authored mixin declarations (the mixins.edn shape).

  Build-time only: the Skein Gradle plugin puts malli on the compile
  classpath when a mixins.edn exists — never require this namespace
  from mod code. defmixin declarations get equivalent (and earlier)
  structural checks from the macro itself; this schema is the source of
  truth for the data path."
  (:require [clojure.pprint :as pprint]
            [malli.core :as m]
            [malli.error :as me]))

(def method-ref
  "A method reference: bare symbol, structural map, vector shortcut, or
  the raw \"name(descriptor)\" string escape hatch."
  [:or
   symbol?
   string?
   [:map
    [:name symbol?]
    [:params {:optional true} [:sequential symbol?]]]
   [:catn
    [:name symbol?]
    [:params [:* symbol?]]]])

(def at-ref
  "An @At: a keyword (:head :tail :return :invoke :field) or a map with
  :value plus an optional structural :target and :ordinal."
  [:or
   keyword?
   [:map
    [:value [:or keyword? string?]]
    [:target {:optional true}
     [:map
      [:owner symbol?]
      [:name symbol?]
      [:params {:optional true} [:sequential symbol?]]]]
    [:ordinal {:optional true} nat-int?]]])

(def injector
  [:and
   [:map
    [:type [:enum :inject :modify-arg :modify-return]]
    [:method method-ref]
    [:handler qualified-symbol?]
    [:at {:optional true} at-ref]
    [:cancellable {:optional true} boolean?]
    [:index {:optional true} nat-int?]
    [:ordinal {:optional true} nat-int?]]
   [:fn {:error/message ":inject and :modify-arg need :at (:modify-return does not take one)"}
    (fn [{:keys [type at]}]
      (case type
        :inject (some? at)
        :modify-arg (some? at)
        :modify-return (nil? at)))]])

(def mixin
  [:map
   [:target symbol?]
   [:class {:optional true} symbol?]
   [:injects [:sequential {:min 1} injector]]])

(def mixins-file
  "The whole mixins.edn: {:mixins [<mixin> ...]}."
  [:map
   [:mixins [:sequential mixin]]])

(defn validate!
  "Throws a build-facing error (humanized malli explanation) when the
  mixins.edn data does not match the schema; returns the data otherwise.
  `source` names the file for the message."
  [data source]
  (if-let [explanation (m/explain mixins-file data)]
    (throw (ex-info (str "Invalid mixin declarations in " source ":\n"
                         (with-out-str (pprint/pprint (me/humanize explanation)))
                         "Expected shape: {:mixins [{:target <class-symbol>"
                         " :class <generated-class-symbol> :injects [{:type :inject|:modify-arg|:modify-return"
                         " :method <symbol|map|vector> :handler <ns/fn-symbol> ...} ...]} ...]}")
                    {:skein/mixin-error true
                     :source source
                     :explanation (me/humanize explanation)}))
    data))
