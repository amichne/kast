package io.github.amichne.kast.workspace.intellij

import org.gradle.util.GradleVersion
import java.nio.file.Path

internal enum class GradleJvmSelectionSource {
    DAEMON_JVM_CRITERIA,
    REPOSITORY_GRADLE_PROPERTY,
    SIDECAR_COMPATIBLE,
    PLATFORM_RESOLVER,
}

/** Detached observation of one physically admitted local Java installation. */
internal data class GradleJvmCandidate(
    val home: Path,
    val feature: JavaFeature,
    val runtimeVersion: String,
    val source: GradleJvmSelectionSource,
) {
    init {
        require(home.isAbsolute)
        require(home.normalize() == home)
        require(runtimeVersion.isNotBlank())
    }
}

internal enum class GradleJvmCandidateSelectionFailure { NO_COMPATIBLE_RUNTIME }

internal sealed interface GradleJvmCandidateSelection {
    data class Selected(
        val distribution: GradleVersion,
        val candidate: GradleJvmCandidate,
    ) : GradleJvmCandidateSelection

    data class Rejected(
        val failure: GradleJvmCandidateSelectionFailure,
    ) : GradleJvmCandidateSelection
}

/** Pure deterministic selection over already detached local-runtime observations. */
internal object GradleJvmCandidateSelector {
    fun select(
        distribution: GradleVersion,
        candidates: List<GradleJvmCandidate>,
    ): GradleJvmCandidateSelection {
        val distinctCandidates = candidates
            .groupBy(GradleJvmCandidate::home)
            .values
            .map { sameHome ->
                sameHome.minWith(
                    compareBy<GradleJvmCandidate>(
                        { candidate -> candidate.source.precedence() },
                        GradleJvmCandidate::runtimeVersion,
                    ),
                )
            }
        val authoritativeCandidates = when {
            distinctCandidates.any {
                candidate -> candidate.source == GradleJvmSelectionSource.DAEMON_JVM_CRITERIA
            } -> distinctCandidates.filter {
                candidate -> candidate.source == GradleJvmSelectionSource.DAEMON_JVM_CRITERIA
            }
            distinctCandidates.any {
                candidate ->
                candidate.source == GradleJvmSelectionSource.REPOSITORY_GRADLE_PROPERTY
            } -> distinctCandidates.filter {
                candidate ->
                candidate.source == GradleJvmSelectionSource.REPOSITORY_GRADLE_PROPERTY
            }
            else -> distinctCandidates
        }
        val selected = authoritativeCandidates
            .filter { candidate ->
                GradleRuntimeCompatibilityPolicy.classify(
                    distribution,
                    candidate.feature,
                ) is GradleRuntimeCompatibility.Compatible
            }
            .minWithOrNull(
                compareBy<GradleJvmCandidate>(
                    { candidate -> candidate.source.precedence() },
                    { candidate -> candidate.feature.value },
                    GradleJvmCandidate::runtimeVersion,
                    { candidate -> candidate.home.toString() },
                ),
            ) ?: return GradleJvmCandidateSelection.Rejected(
            GradleJvmCandidateSelectionFailure.NO_COMPATIBLE_RUNTIME,
        )
        return GradleJvmCandidateSelection.Selected(distribution, selected)
    }

    private fun GradleJvmSelectionSource.precedence(): Int = when (this) {
        GradleJvmSelectionSource.DAEMON_JVM_CRITERIA -> 0
        GradleJvmSelectionSource.REPOSITORY_GRADLE_PROPERTY -> 1
        GradleJvmSelectionSource.SIDECAR_COMPATIBLE -> 2
        GradleJvmSelectionSource.PLATFORM_RESOLVER -> 3
    }
}

/** Gradle JVM authority established only from a compatible candidate-selection proof. */
class SelectedGradleJvm private constructor(
    internal val home: Path,
    internal val feature: JavaFeature,
    internal val runtimeVersion: String,
    internal val source: GradleJvmSelectionSource,
    internal val distribution: GradleVersion,
    private val selector: String,
) {
    /** Raw selector extraction is confined to the Gradle project-settings boundary. */
    internal fun projectSettingsSelector(): String = selector

    companion object {
        /** Refines a compatible selection and its IntelliJ SDK selector into Gradle authority. */
        internal fun establish(
            selection: GradleJvmCandidateSelection.Selected,
            selector: String,
        ): SelectedGradleJvm {
            require(selector.isNotBlank())
            val candidate = selection.candidate
            return SelectedGradleJvm(
                home = candidate.home,
                feature = candidate.feature,
                runtimeVersion = candidate.runtimeVersion,
                source = candidate.source,
                distribution = selection.distribution,
                selector = selector,
            )
        }
    }
}
