package io.github.amichne.kast.standalone.workspace

internal enum class GradleSourceSet(
    val id: String,
    val supportedDependencyScopes: Set<GradleDependencyScope>,
) {
    MAIN(
        id = "main",
        supportedDependencyScopes = setOf(
            GradleDependencyScope.COMPILE,
            GradleDependencyScope.PROVIDED,
            GradleDependencyScope.RUNTIME,
            GradleDependencyScope.UNKNOWN,
        ),
    ),
    TEST_FIXTURES(
        id = "testFixtures",
        supportedDependencyScopes = setOf(
            GradleDependencyScope.COMPILE,
            GradleDependencyScope.PROVIDED,
            GradleDependencyScope.TEST_FIXTURES,
            GradleDependencyScope.RUNTIME,
            GradleDependencyScope.UNKNOWN,
        ),
    ),
    TEST(
        id = "test",
        supportedDependencyScopes = setOf(
            GradleDependencyScope.COMPILE,
            GradleDependencyScope.PROVIDED,
            GradleDependencyScope.TEST,
            GradleDependencyScope.TEST_FIXTURES,
            GradleDependencyScope.RUNTIME,
            GradleDependencyScope.UNKNOWN,
        ),
    ),
}
