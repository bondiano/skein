(ns skein-example-test.harness
  "clojure.test -> JUnit bridge for the headless Fabric integration
  tests. fabric-loader-junit needs a JUnit test class to boot the real
  loader inside the JVM, but the tests themselves are plain clojure.test
  `deftest`s: `suites` maps each test namespace to a JUnit
  DynamicContainer whose children run the test vars one by one, in
  declaration order, converting clojure.test failure reports into JUnit
  assertion errors. The game boot (vanilla bootstrap + mod entrypoint,
  the production phase order) is owned by the Java @BeforeAll and runs
  before the factory enumerates the suites."
  (:require [clojure.string :as str]
            [clojure.test :as t])
  (:import (org.junit.jupiter.api DynamicContainer DynamicNode DynamicTest)
           (org.junit.jupiter.api.function Executable)
           (org.opentest4j AssertionFailedError)))

;;; clojure.test var -> JUnit DynamicTest

(defn- format-problem [{:keys [type context message expected actual]}]
  (str/join "\n"
            (remove nil?
                    [(str (name type) (when (seq context) (str " in " context)))
                     (when message (str "  message:  " message))
                     (str "  expected: " (pr-str expected))
                     (str "  actual:   " (pr-str actual))])))

(defn- throw-on-problems! [v problems]
  (let [errors (filter (comp #{:error} :type) problems)]
    (if (and (= 1 (count problems)) (= 1 (count errors))
             (instance? Throwable (:actual (first errors))))
      ;; A lone uncaught exception: rethrow as-is, the stack trace is
      ;; worth more than any reformatting.
      (throw ^Throwable (:actual (first errors)))
      (throw (AssertionFailedError.
              (str (-> v meta :name) " — " (count problems) " failed assertion(s):\n"
                   (str/join "\n" (map format-problem problems))))))))

(defn- run-test-var!
  "Runs one deftest var, collecting clojure.test reports; :fail/:error
  reports become a JUnit assertion error."
  [v]
  (let [reports (atom [])]
    (binding [t/report (fn [m]
                         (swap! reports conj
                                (assoc m :context (t/testing-contexts-str))))]
      (t/test-var v))
    (when-let [problems (seq (filter (comp #{:fail :error} :type) @reports))]
      (throw-on-problems! v problems))))

(def ^:private game-classloader
  "The classloader that loaded the shared Clojure runtime — under Fabric
  that is Knot, which resolves the game classes to the bootstrapped copy.
  JUnit runs tests with the app classloader as the thread context, whose
  separate (un-bootstrapped) copy of the Minecraft classes a test would
  otherwise reach when it uses a game class as a value (RT.baseLoader
  resolves such class literals through the context classloader)."
  (.getClassLoader skein.runtime.ClojureRuntime))

(defn- test-node ^DynamicTest [v]
  (DynamicTest/dynamicTest
   (str (-> v meta :name))
   (reify Executable
     (execute [_]
       (let [thread (Thread/currentThread)
             previous (.getContextClassLoader thread)]
         (.setContextClassLoader thread game-classloader)
         (try
           (run-test-var! v)
           (finally
             (.setContextClassLoader thread previous))))))))

(defn- suite-node ^DynamicNode [ns-sym]
  ;; Load through the shared runtime, not plain `require`: JUnit's thread
  ;; context classloader is the app classloader, and compiling against it
  ;; would parent the new classes on the app-classpath copy of Clojure
  ;; instead of Knot's.
  (.requireNamespace (skein.runtime.ClojureRuntime/get) (str ns-sym))
  (let [test-vars (->> (vals (ns-interns ns-sym))
                       (filter (comp :test meta))
                       (sort-by (comp :line meta)))]
    (when (empty? test-vars)
      (throw (AssertionFailedError. (str "no deftest vars in " ns-sym))))
    (DynamicContainer/dynamicContainer
     (str ns-sym)
     ^Iterable (mapv test-node test-vars))))

(defn suites
  "For each namespace name (a string) returns a JUnit DynamicContainer
  with one DynamicTest per deftest, in declaration order. Entry point
  for the Java @TestFactory bridge (called after the game is booted)."
  [ns-names]
  (mapv (comp suite-node symbol) ns-names))
