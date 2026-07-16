/**
 * Runtime glue between the Clojure runtime and the Fabric loader.
 *
 * <p>M0 — {@code DynamicClassLoader} parented on Knot, so Clojure sees the
 * game and other mods' classpath (DESIGN.md §4.1). MC 26.1+ is unobfuscated,
 * so dev and production share the same class/method names — no mapping
 * resolution or Reflector shim is needed (§6).
 */
package skein.runtime;
