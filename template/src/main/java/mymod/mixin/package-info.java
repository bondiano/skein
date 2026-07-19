/**
 * Hand-written Java mixins — the escape hatch for the rare cases the
 * data-driven {@code defmixin} (see {@code mymod.mixin}) does not cover
 * (complex {@code @At} selectors, MixinExtras operations, local capture, …).
 *
 * <p>Prefer {@code defmixin}: it verifies the target at compile time, generates
 * the mixin class, the mixins json and the {@code fabric.mod.json} entry, and
 * keeps the handler hot-reloadable behind a var. Reach for a Java mixin only
 * when the declarative form cannot express what you need.
 *
 * <p>To use this escape hatch:
 * <ol>
 *   <li>drop a {@code @Mixin}-annotated class in this package;</li>
 *   <li>call your Clojure logic from it through a var so it still hot-reloads
 *       (e.g. resolve {@code mymod.core/some-fn} once and invoke the {@code IFn});</li>
 *   <li>list the class in a {@code mymod.mixins.json} in {@code src/main/resources};</li>
 *   <li>add a {@code "mixins": ["mymod.mixins.json"]} entry to
 *       {@code fabric.mod.json}.</li>
 * </ol>
 *
 * <p>This package-info exists only to document the pattern and keep the
 * package tracked in version control; delete it once you add a real mixin, or
 * leave the package empty if you only ever use {@code defmixin}.
 */
package mymod.mixin;
