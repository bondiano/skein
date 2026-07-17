(ns skein.mixin.defmixin-test
  "The macro end of the pipeline: expansion resolves the target right
  away, defines the handler fns and registers the resolved declaration."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skein.mixin :as mixin :refer [defmixin]]))

;; Expands at load time — this is the moment a typo would fail, in the
;; editor/REPL, before any gradle build.
(defmixin list-mixin
  {:target java.util.ArrayList
   :class skein.gen.DefmixinListMixin}
  (inject {:at :head :cancellable true}
    (trimToSize [this ci]
      [:trimmed this ci])))

(deftest defmixin-defines-the-resolved-declaration
  (is (= "java.util.ArrayList" (:target list-mixin)))
  (is (= "skein.gen.DefmixinListMixin" (:class list-mixin)))
  (is (= [{:type :inject
           :method {:name "trimToSize" :desc "()V" :static false
                    :param-descs [] :return-desc "V"}
           :at {:value "HEAD"}
           :cancellable true
           :handler "skein.mixin.defmixin-test/list-mixin-trimToSize"}]
         (:injects list-mixin))))

(deftest defmixin-defines-a-plain-hot-reloadable-handler
  (is (= [:trimmed :a :b] (list-mixin-trimToSize :a :b)))
  (is (var? #'list-mixin-trimToSize)))

(deftest defmixin-registers-into-the-build-registry
  (is (some #(= "skein.gen.DefmixinListMixin" (:class %)) (mixin/declarations))))

(defn- expansion-error
  "macroexpand wraps macro throws into a CompilerException — dig the
  :skein/mixin-error message out of the cause chain, like the build
  pipeline does."
  [form]
  (try
    (macroexpand form)
    nil
    (catch Throwable t
      (->> (iterate #(.getCause ^Throwable %) t)
           (take-while some?)
           (some #(when (and (instance? clojure.lang.ExceptionInfo %)
                             (:skein/mixin-error (ex-data %)))
                    (.getMessage ^Throwable %)))))))

(deftest defmixin-fails-fast-on-bad-declarations
  (is (str/includes? (expansion-error '(skein.mixin/defmixin broken
                                         {:target java.util.ArrayList}
                                         (inject {:at :head}
                                           (noSuchMethod [this ci] nil))))
                     "no method named 'noSuchMethod'"))
  (is (str/includes? (expansion-error '(skein.mixin/defmixin broken
                                         {:target java.util.ArrayList}))
                     "no injectors"))
  (is (str/includes? (expansion-error '(skein.mixin/defmixin broken
                                         {:target java.util.ArrayList}
                                         (redirect {} (x [this ci] nil))))
                     "injector form"))
  (is (str/includes? (expansion-error '(skein.mixin/defmixin broken
                                         {:target java.util.ArrayList}
                                         (inject {:at :head} (trimToSize [this ci] nil))
                                         (inject {:at :tail} (trimToSize [this ci] nil))))
                     "both define handler")))

(deftest defmixin-arity-mismatch-names-the-expected-args
  (let [message (expansion-error '(skein.mixin/defmixin broken
                                    {:target java.util.ArrayList}
                                    (inject {:at :head}
                                      (trimToSize [this one-too-many ci] nil))))]
    (is (str/includes? message "fits the handler"))))
