package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.distribution.contract.gradle.GradleDistributionEvidence
import io.github.amichne.kast.distribution.contract.gradle.GradleDistributionVersion
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentIdentity
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmCandidateDecision
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmCandidateEvidence
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionFailure
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionOutcome
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionReport
import org.gradle.util.GradleVersion

/** Pure bounded report projection. Physical JDK paths appear only as stable identity digests. */
internal fun gradleJvmSelectedReport(
    selection: GradleJvmCandidateSelection.Selected,
    candidates: List<GradleJvmCandidate>,
): GradleJvmSelectionReport {
    val selected = selection.candidate.evidence(GradleJvmCandidateDecision.SELECTED)
    return GradleJvmSelectionReport(
        distribution = distributionEvidence(selection.distribution),
        requiredJava = GradleRuntimeCompatibilityPolicy.requiredJava(selection.distribution),
        candidates = listOf(selected) + candidates.sortedCandidates()
            .filter { it.home != selection.candidate.home }
            .take(31)
            .map { it.evidence(it.unselectedDecision(selection.distribution)) },
        outcome = GradleJvmSelectionOutcome.Selected(selected),
    )
}

internal fun gradleJvmRejectionReport(
    distribution: GradleVersion,
    candidates: List<GradleJvmCandidate>,
    failure: GradleJvmSelectionFailure,
): GradleJvmSelectionReport = GradleJvmSelectionReport(
    distributionEvidence(distribution),
    GradleRuntimeCompatibilityPolicy.requiredJava(distribution),
    candidates.sortedCandidates().take(32).map { candidate ->
        candidate.evidence(candidate.unselectedDecision(distribution))
    },
    GradleJvmSelectionOutcome.Rejected(failure),
)

private fun distributionEvidence(version: GradleVersion) =
    GradleDistributionEvidence.Observed(GradleDistributionVersion.observed(version.version))

private fun List<GradleJvmCandidate>.sortedCandidates(): List<GradleJvmCandidate> =
    distinctBy { it.home }.sortedWith(compareBy({ it.feature.value }, { it.home.toString() }))

private fun GradleJvmCandidate.unselectedDecision(distribution: GradleVersion): GradleJvmCandidateDecision =
    when (GradleRuntimeCompatibilityPolicy.classify(distribution, feature)) {
        is GradleRuntimeCompatibility.Incompatible -> GradleJvmCandidateDecision.INCOMPATIBLE_GRADLE
        is GradleRuntimeCompatibility.Compatible -> GradleJvmCandidateDecision.NOT_SELECTED
    }

private fun GradleJvmCandidate.evidence(decision: GradleJvmCandidateDecision) = GradleJvmCandidateEvidence(
    feature,
    GradleImportEnvironmentIdentity.digest("$home\n$runtimeVersion"),
    source,
    decision,
)
