(ns skein-example-test.loader-test
  "The loading chain in the headless Fabric env: fabric-loader-junit
  boots the real loader (Knot classloading, mod discovery, language
  adapters) in this JVM, so the whole chain is exercised —
  fabric.mod.json of the adapter registers the \"clojure\" language
  adapter, and this mod's `skein-example.core/init` entrypoint resolves
  through it into an AOT-compiled Clojure fn."
  (:require [clojure.test :refer [deftest is]]
            [skein-example.core :as example])
  (:import (java.lang.reflect Proxy)
           (net.fabricmc.api ModInitializer)
           (net.fabricmc.loader.api FabricLoader)
           (net.fabricmc.loader.api.entrypoint EntrypointContainer)))

(defn- example-mod-entrypoint
  "The example mod's `main` entrypoint as the loader resolved it. The
  loader caches the instance, so this returns the same SAM proxy the
  @BeforeAll boot already ran."
  ^ModInitializer []
  (->> (.getEntrypointContainers (FabricLoader/getInstance) "main" ModInitializer)
       (filter (fn [^EntrypointContainer c]
                 (= "skein_example" (.getId (.getMetadata (.getProvider c))))))
       first
       (#(.getEntrypoint ^EntrypointContainer %))))

(deftest loader-discovers-adapter-and-example-mod
  (let [loader (FabricLoader/getInstance)]
    (is (.isModLoaded loader "skein") "adapter mod discovered")
    (is (.isModLoaded loader "skein_example") "example mod discovered")))

(deftest clojure-main-entrypoint-resolves-and-runs
  (let [initializer (example-mod-entrypoint)]
    (is (Proxy/isProxyClass (class initializer)) "fn wrapped into a SAM proxy")
    ;; Idempotent: the @BeforeAll boot already ran it once, exactly like a
    ;; second registration pass — a re-run must not throw.
    (.onInitialize initializer)
    (is (true? @example/initialized?) "Clojure init fn actually ran")))
