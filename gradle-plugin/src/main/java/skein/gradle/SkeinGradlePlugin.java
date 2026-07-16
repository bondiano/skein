package skein.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Skein Gradle plugin, applied on top of Fabric Loom (DESIGN.md §11).
 *
 * <p>M1 scope: {@code src/main/clojure} source set, AOT compilation ordered
 * before {@code jar}, reflection perf-lint ({@code *warn-on-reflection*};
 * MC 26.1+ is unobfuscated, so reflection is a performance concern, not a
 * correctness one), ns-naming lint, ban on JiJ-ing a mod's own clojure.jar.
 */
public final class SkeinGradlePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // M1: wire Loom, clojure source set, AOT ordering, hints enforcement.
        project.getLogger().lifecycle(
                "Skein Gradle plugin applied to {} — skeleton only, functionality lands in M1", project.getPath());
    }
}
