package io.github.amichne.kast.workspace.intellij

import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GradleJvmCandidateSelectorTest {
    @Test
    fun `old wrapper selects Java 17 instead of incompatible sidecar Java 25`() {
        val selected = assertInstanceOf(
            GradleJvmCandidateSelection.Selected::class.java,
            GradleJvmCandidateSelector.select(
                GradleVersion.version("7.6"),
                listOf(sidecar(25), installed(17)),
            ),
        )

        assertEquals(JavaFeature.of(17), selected.candidate.feature)
        assertEquals(GradleJvmSelectionSource.PLATFORM_RESOLVER, selected.candidate.source)
    }

    @Test
    fun `compatible sidecar wins before platform candidates`() {
        val selected = assertInstanceOf(
            GradleJvmCandidateSelection.Selected::class.java,
            GradleJvmCandidateSelector.select(
                GradleVersion.version("9.1.0"),
                listOf(installed(17), sidecar(25)),
            ),
        )

        assertEquals(JavaFeature.of(25), selected.candidate.feature)
        assertEquals(GradleJvmSelectionSource.SIDECAR_COMPATIBLE, selected.candidate.source)
    }

    @Test
    fun `candidate order cannot change selection`() {
        val gradle = GradleVersion.version("7.6")
        val candidates = listOf(installed(21), installed(17), sidecar(25))

        assertEquals(
            GradleJvmCandidateSelector.select(gradle, candidates),
            GradleJvmCandidateSelector.select(gradle, candidates.reversed()),
        )
    }

    @Test
    fun `daemon criteria has stronger selection authority than a compatible sidecar`() {
        val selected = assertInstanceOf(
            GradleJvmCandidateSelection.Selected::class.java,
            GradleJvmCandidateSelector.select(
                GradleVersion.version("9.1.0"),
                listOf(
                    sidecar(25),
                    installed(17).copy(source = GradleJvmSelectionSource.DAEMON_JVM_CRITERIA),
                ),
            ),
        )

        assertEquals(JavaFeature.of(17), selected.candidate.feature)
        assertEquals(GradleJvmSelectionSource.DAEMON_JVM_CRITERIA, selected.candidate.source)
    }

    @Test
    fun `incompatible repository Java home fails instead of falling through to platform Java`() {
        val rejected = assertInstanceOf(
            GradleJvmCandidateSelection.Rejected::class.java,
            GradleJvmCandidateSelector.select(
                GradleVersion.version("7.6"),
                listOf(
                    installed(17),
                    installed(25).copy(
                        source = GradleJvmSelectionSource.REPOSITORY_GRADLE_PROPERTY,
                    ),
                ),
            ),
        )

        assertEquals(
            GradleJvmCandidateSelectionFailure.NO_COMPATIBLE_RUNTIME,
            rejected.failure,
        )
    }

    private fun sidecar(feature: Int): GradleJvmCandidate = GradleJvmCandidate(
        home = Path.of("/fixture/sidecar-$feature"),
        feature = JavaFeature.of(feature),
        runtimeVersion = "fixture-$feature",
        source = GradleJvmSelectionSource.SIDECAR_COMPATIBLE,
    )

    private fun installed(feature: Int): GradleJvmCandidate = GradleJvmCandidate(
        home = Path.of("/fixture/installed-$feature"),
        feature = JavaFeature.of(feature),
        runtimeVersion = "fixture-$feature",
        source = GradleJvmSelectionSource.PLATFORM_RESOLVER,
    )
}
