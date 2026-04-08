package io.github.amichne.kast.standalone.workspace

import kotlinx.serialization.Serializable
import org.gradle.tooling.model.idea.IdeaDependency

@Serializable
internal enum class GradleDependencyScope {
    COMPILE,
    PROVIDED,
    TEST,
    TEST_FIXTURES,
    RUNTIME,
    UNKNOWN,
    ;

    companion object {
        fun from(dependency: IdeaDependency): GradleDependencyScope = when (dependency.scope?.scope?.uppercase()) {
            "COMPILE" -> COMPILE
            "PROVIDED" -> PROVIDED
            "TEST" -> TEST
            "TEST_FIXTURES" -> TEST_FIXTURES
            "RUNTIME" -> RUNTIME
            else -> UNKNOWN
        }
    }
}
