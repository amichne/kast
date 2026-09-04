package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.distribution.contract.gradle.*
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GradleJvmSelectionEvidenceTest {
    @Test
    fun `selection report carries wrapper range selected authority and rejected candidates`() {
        val candidates = listOf(candidate(17), candidate(25))
        val selected = GradleJvmCandidateSelector.select(GradleVersion.version("7.6"), candidates) as GradleJvmCandidateSelection.Selected
        val report = gradleJvmSelectedReport(selected, candidates)
        assertEquals("7.6", (report.distribution as GradleDistributionEvidence.Observed).version.value)
        assertEquals((8..19).toList(), report.requiredJava.map { it.value })
        assertEquals(GradleJavaFeature.of(17), (report.outcome as GradleJvmSelectionOutcome.Selected).candidate.java)
        assertEquals(GradleJvmCandidateDecision.INCOMPATIBLE_GRADLE, report.candidates.last().decision)
        assertFalse(report.toString().contains("/private/jdk"))
    }

    @Test
    fun `failure report retains available candidates and corrective action`() {
        val report = gradleJvmRejectionReport(GradleVersion.version("7.6"), listOf(candidate(25)), GradleJvmSelectionFailure.NO_COMPATIBLE_RUNTIME)
        assertEquals(listOf(25), report.candidates.map { it.java.value })
        val failure = (report.outcome as GradleJvmSelectionOutcome.Rejected).failure
        assertTrue(failure.correctiveAction.contains("org.gradle.java.home"))
        assertTrue(failure.correctiveAction.contains("requiredJava"))
    }

    @Test
    fun `report candidate evidence is bounded and deterministic`() {
        val candidates = (1..100).map { candidate(17, it) }
        val first = gradleJvmRejectionReport(GradleVersion.version("7.6"), candidates, GradleJvmSelectionFailure.SDK_REGISTRATION_FAILED)
        val reversed = gradleJvmRejectionReport(GradleVersion.version("7.6"), candidates.reversed(), GradleJvmSelectionFailure.SDK_REGISTRATION_FAILED)
        assertEquals(32, first.candidates.size)
        assertEquals(first, reversed)
    }

    private fun candidate(feature: Int, suffix: Int = feature) = GradleJvmCandidate(
        Path.of("/private/jdk/$suffix"), JavaFeature.of(feature), "$feature.0.1", GradleJvmSelectionSource.PLATFORM_RESOLVER,
    )
}
