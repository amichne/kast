package io.github.amichne.kast.workspace.intellij

import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class GradleRuntimeCompatibilityPolicyTest {
    @Test
    fun `Java 17 requires Gradle 7_3`() {
        assertRejected("7.2.2", 17)
        assertCompatible("7.3", 17)
    }

    @Test
    fun `Java 21 requires Gradle 8_5`() {
        assertRejected("8.4", 21)
        assertCompatible("8.5", 21)
    }

    @Test
    fun `Java 25 requires Gradle 9_1`() {
        assertRejected("9.0.0", 25)
        assertCompatible("9.1.0", 25)
    }

    @Test
    fun `classification is deterministic`() {
        val input = GradleVersion.version("7.6") to JavaFeature.of(17)

        assertEquals(
            GradleRuntimeCompatibilityPolicy.classify(input.first, input.second),
            GradleRuntimeCompatibilityPolicy.classify(input.first, input.second),
        )
    }

    private fun assertCompatible(gradle: String, java: Int) {
        assertInstanceOf(
            GradleRuntimeCompatibility.Compatible::class.java,
            GradleRuntimeCompatibilityPolicy.classify(
                GradleVersion.version(gradle),
                JavaFeature.of(java),
            ),
        )
    }

    private fun assertRejected(gradle: String, java: Int) {
        assertInstanceOf(
            GradleRuntimeCompatibility.Incompatible::class.java,
            GradleRuntimeCompatibilityPolicy.classify(
                GradleVersion.version(gradle),
                JavaFeature.of(java),
            ),
        )
    }
}
