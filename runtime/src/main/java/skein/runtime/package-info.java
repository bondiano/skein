/**
 * Runtime glue between the Clojure runtime and the Fabric loader.
 *
 * <p>{@code DynamicClassLoader} is parented on Knot, so Clojure sees the
 * game and other mods' classpath. MC 26.1+ is unobfuscated, so dev and
 * production share the same class/method names — no mapping resolution or
 * Reflector shim is needed.
 */
package skein.runtime;
