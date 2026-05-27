package io.github.amichne.kast.standalone.workspace

import kotlinx.serialization.Serializable

@Serializable
internal enum class GradleDependencyScope {
    COMPILE,
    PROVIDED,
    TEST,
    TEST_FIXTURES,
    RUNTIME,
    UNKNOWN,
    ;
}
