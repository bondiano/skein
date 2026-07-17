(ns skein.mixin.resolve-test
  "Resolution against plain JDK classes — no game jar needed: the
  resolver only cares about a classpath with real classes on it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.mixin.resolve :as resolve]))

(defn- mixin-error
  "The message of the :skein/mixin-error thunk throws, nil otherwise."
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo e
      (when (:skein/mixin-error (ex-data e))
        (.getMessage e)))))

(defn- hinted [sym tag] (with-meta sym {:tag tag}))

(deftest class-named-resolves-and-fails-clearly
  (is (= String (resolve/class-named 'java.lang.String)))
  (is (= Integer/TYPE (resolve/class-named 'int)))
  (let [message (mixin-error #(resolve/class-named 'net.nope.Missing))]
    (is (str/includes? message "net.nope.Missing"))
    (is (str/includes? message "compile classpath"))))

(deftest inject-resolves-an-unambiguous-method
  (let [ir (resolve/resolve-injector
            java.util.ArrayList
            {:type :inject :method 'trimToSize :at :head :cancellable true
             :handler 'my.mod/on-trim}
            {:argv '[this ci]})]
    (is (= {:type :inject
            :method {:name "trimToSize" :desc "()V" :static false
                     :param-descs [] :return-desc "V"}
            :at {:value "HEAD"}
            :cancellable true
            :handler "my.mod/on-trim"}
           ir))))

(deftest inject-picks-the-overload-fitting-the-handler-arity
  ;; String.substring is overloaded (int) / (int int); [this from ci]
  ;; only fits the single-arg one.
  (let [ir (resolve/resolve-injector
            String
            {:type :inject :method 'substring :at :head :handler 'my.mod/h}
            {:argv '[this from ci]})]
    (is (= "(I)Ljava/lang/String;" (get-in ir [:method :desc])))))

(deftest ambiguous-overloads-list-candidates-structurally
  ;; Static String.valueOf has many single-arg overloads; arity alone
  ;; cannot pick one.
  (let [message (mixin-error
                 #(resolve/resolve-injector
                   String
                   {:type :inject :method 'valueOf :at :head :handler 'my.mod/h}
                   {:argv '[x ci]}))]
    (is (str/includes? message "ambiguous"))
    (is (str/includes? message "{:name valueOf :params [long]}"))
    (is (str/includes? message "type hints"))))

(deftest type-hints-disambiguate-overloads
  (let [ir (resolve/resolve-injector
            String
            {:type :inject :method 'valueOf :at :head :handler 'my.mod/h}
            {:argv [(hinted 'x 'long) 'ci]})]
    (is (= "(J)Ljava/lang/String;" (get-in ir [:method :desc])))
    (is (true? (get-in ir [:method :static])))))

(deftest structural-params-disambiguate-without-hints
  (let [ir (resolve/resolve-injector
            String
            {:type :inject :method {:name 'valueOf :params '[boolean]} :at :head
             :handler 'my.mod/h}
            {})]
    (is (= "(Z)Ljava/lang/String;" (get-in ir [:method :desc])))))

(deftest missing-method-lists-what-is-there
  (let [message (mixin-error
                 #(resolve/resolve-injector
                   java.util.ArrayList
                   {:type :inject :method 'tikcServer :at :head :handler 'my.mod/h}
                   {}))]
    (is (str/includes? message "no method named 'tikcServer'"))
    (is (str/includes? message "trimToSize"))))

(deftest modify-return-resolves-and-rejects-void
  (let [ir (resolve/resolve-injector
            String
            {:type :modify-return :method 'length :handler 'my.mod/fake-length}
            {:argv '[this ret]})]
    (is (= :modify-return (:type ir)))
    (is (= "I" (get-in ir [:method :return-desc]))))
  (let [message (mixin-error
                 #(resolve/resolve-injector
                   java.util.ArrayList
                   {:type :modify-return :method 'trimToSize :handler 'my.mod/h}
                   {:argv '[this ret]}))]
    (is (str/includes? message "returns void"))))

(deftest modify-arg-resolves-the-invoked-argument
  (let [ir (resolve/resolve-injector
            StringBuilder
            {:type :modify-arg
             :method 'toString
             :at {:value :invoke :target {:owner 'java.lang.String
                                          :name 'valueOf
                                          :params '[java.lang.Object]}}
             :handler 'my.mod/h}
            {:argv '[arg]})]
    (is (= 0 (:index ir)))
    (is (= "Ljava/lang/Object;" (:arg-desc ir)))
    (is (= "Ljava/lang/String;valueOf(Ljava/lang/Object;)Ljava/lang/String;"
           (get-in ir [:at :target])))))

(deftest modify-arg-demands-an-index-for-multi-arg-invokes
  (let [message (mixin-error
                 #(resolve/resolve-injector
                   StringBuilder
                   {:type :modify-arg
                    :method 'toString
                    :at {:value :invoke :target {:owner 'java.lang.StringBuilder
                                                 :name 'insert
                                                 :params '[int java.lang.String]}}
                    :handler 'my.mod/h}
                   {:argv '[arg]}))]
    (is (str/includes? message ":index"))))

(deftest field-at-targets-resolve
  (let [{:keys [at]} (resolve/resolve-at {:value :field :target {:owner 'java.lang.System
                                                                 :name 'out}}
                                         "test")]
    (is (= {:value "FIELD" :target "Ljava/lang/System;out:Ljava/io/PrintStream;"} at))))

(deftest whole-edn-declarations-resolve
  (testing "the mixins.edn shape, symbols and all"
    (let [ir (resolve/resolve-declaration
              {:target 'java.util.ArrayList
               :class 'mymod.skein_mixins.ListMixin
               :injects [{:type :inject :method 'trimToSize :at :head
                          :handler 'mymod.core/on-trim}]})]
      (is (= "java.util.ArrayList" (:target ir)))
      (is (= "mymod.skein_mixins.ListMixin" (:class ir)))
      (is (= 1 (count (:injects ir)))))))
