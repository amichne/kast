package io.github.amichne.kast.workspace.intellij

import java.nio.file.Path
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class GradleJvmFallbackPreferenceTest {
    @Test
    fun `incompatible Java 25 falls back to Java 21 rather than Java 8 regardless of discovery order`() {
        val java21 = candidate(21, GradleJvmSelectionSource.PLATFORM_RESOLVER)
        val candidates =
            listOf(
                candidate(25, GradleJvmSelectionSource.AMBIENT_JAVA_HOME),
                candidate(25, GradleJvmSelectionSource.SIDECAR_COMPATIBLE),
                candidate(8, GradleJvmSelectionSource.PLATFORM_RESOLVER),
                java21,
            )
        for (order in listOf(candidates, candidates.reversed())) {
            val selected =
                assertInstanceOf(
                    GradleJvmCandidateSelection.Selected::class.java,
                    GradleJvmCandidateSelector.select(GradleVersion.version("8.14"), order),
                )
            assertEquals(java21, selected.candidate)
        }
    }

    @Test
    fun `compatible ambient JVM retains precedence over newer discovered JVM`() {
        val ambient = candidate(17, GradleJvmSelectionSource.AMBIENT_JAVA_HOME)
        val selected =
            assertInstanceOf(
                GradleJvmCandidateSelection.Selected::class.java,
                GradleJvmCandidateSelector.select(
                    GradleVersion.version("8.14"),
                    listOf(candidate(21, GradleJvmSelectionSource.PLATFORM_RESOLVER), ambient),
                ),
            )
        assertEquals(ambient, selected.candidate)
    }

    @Test
    fun `incompatible repository JVM is not replaced by a discovered JVM`() {
        assertInstanceOf(
            GradleJvmCandidateSelection.Rejected::class.java,
            GradleJvmCandidateSelector.select(
                GradleVersion.version("8.14"),
                listOf(
                    candidate(25, GradleJvmSelectionSource.REPOSITORY_GRADLE_PROPERTY),
                    candidate(21, GradleJvmSelectionSource.PLATFORM_RESOLVER),
                ),
            ),
        )
    }

    private fun candidate(feature: Int, source: GradleJvmSelectionSource) =
        GradleJvmCandidate(
            home = Path.of("/jdks/${source.name}/$feature"),
            feature = JavaFeature.of(feature),
            runtimeVersion = "$feature.0.1",
            source = source,
        )
}
