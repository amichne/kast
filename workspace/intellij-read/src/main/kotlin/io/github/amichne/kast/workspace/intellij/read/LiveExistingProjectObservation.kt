package io.github.amichne.kast.workspace.intellij.read

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.KotlinPluginBuildIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider

/** Finite observation stages at the existing-Project boundary. */
enum class ExistingProjectObservationStage {
    DISPOSAL,
    OPEN,
    INITIALIZATION,
    ROOT,
    GRADLE_MODEL,
    INDEXING,
    KOTLIN_MODE,
    HOST_IDENTITY,
}

/** Closed expected failures for `Project -> AdmittedIdeProject`. */
sealed interface ExistingProjectAdmissionFailure {
    data object ProjectDisposed : ExistingProjectAdmissionFailure
    data object ProjectNotOpen : ExistingProjectAdmissionFailure
    data object ProjectNotInitialized : ExistingProjectAdmissionFailure
    data object ProjectRootUnavailable : ExistingProjectAdmissionFailure
    data object ProjectRootMismatch : ExistingProjectAdmissionFailure
    data object GradleModelUnavailable : ExistingProjectAdmissionFailure
    data object GradleModelIncomplete : ExistingProjectAdmissionFailure
    data object DumbMode : ExistingProjectAdmissionFailure
    data object K2Unavailable : ExistingProjectAdmissionFailure
    data object HostIdentityUnavailable : ExistingProjectAdmissionFailure
    data object RetainedAuthorityMismatch : ExistingProjectAdmissionFailure
    data class HostIncompatible(
        val cause: IdeHostCompatibilityFailure,
    ) : ExistingProjectAdmissionFailure
    data class ObservationFailed(
        val stage: ExistingProjectObservationStage,
    ) : ExistingProjectAdmissionFailure
}

/** Closed result of attempting to admit one existing IntelliJ Project. */
sealed interface ExistingProjectAdmission {
    data class Admitted(val project: AdmittedIdeProject) : ExistingProjectAdmission
    data class Rejected(val failure: ExistingProjectAdmissionFailure) : ExistingProjectAdmission
}

/** Cached Gradle model state observed without import or repair. */
enum class ExistingProjectGradleModelState { UNAVAILABLE, INCOMPLETE, COMPLETE }

/** Current IntelliJ indexing state observed without waiting. */
enum class ExistingProjectIndexingState { DUMB, SMART }

/** Current Kotlin frontend mode observed from the installed Kotlin plugin. */
enum class ExistingProjectKotlinMode { K1, K2 }

/** Detached result of refining the supplied Project root. */
sealed interface ExistingProjectRootObservation {
    data class Available(val root: CanonicalWorkspaceRoot) : ExistingProjectRootObservation
    data object Mismatch : ExistingProjectRootObservation
    data object Unavailable : ExistingProjectRootObservation
}

/** Detached running-host identity observed from the loaded IDE and Kotlin plugins. */
sealed interface ExistingProjectHostIdentityObservation {
    data class Available(
        val ideBuild: IdeBuildIdentity,
        val kotlinPluginBuild: KotlinPluginBuildIdentity,
    ) : ExistingProjectHostIdentityObservation

    data class Rejected(
        val failure: IdeHostCompatibilityFailure,
    ) : ExistingProjectHostIdentityObservation

    data object Unavailable : ExistingProjectHostIdentityObservation
}

/** Closed comparison of one platform path with the already-admitted canonical root. */
internal enum class ExistingProjectPathMatch {
    EXACT,
    MISMATCH,
    UNAVAILABLE,
}

/** Closed readiness of one cached external-project structure. */
internal enum class ExistingProjectStructureState { READY, INCOMPLETE }

/** Closed import recency derived from cached IntelliJ timestamps. */
internal enum class ExistingProjectImportState { CURRENT, ABSENT, STALE }

/** Detached cached Gradle-model evidence used by the pure readiness classifier. */
internal data class ExistingProjectGradleModelObservation(
    val pathMatch: ExistingProjectPathMatch,
    val structure: ExistingProjectStructureState,
    val importState: ExistingProjectImportState,
)

/**
 * Proof transition: `(String?, CanonicalWorkspaceRoot) -> ExistingProjectPathMatch`.
 *
 * Establishes exact lexical equality with the already-canonical root without manufacturing a
 * canonical proof from platform text. Missing, malformed, relative, and non-normalized values
 * remain [ExistingProjectPathMatch.UNAVAILABLE]. Raw path extraction is permitted only at the
 * live IntelliJ model boundary.
 */
internal fun observeCanonicalPath(
    raw: String?,
    expectedRoot: CanonicalWorkspaceRoot,
): ExistingProjectPathMatch {
    if (raw == null) return ExistingProjectPathMatch.UNAVAILABLE
    val observed = try {
        Path.of(raw)
    } catch (_: RuntimeException) {
        return ExistingProjectPathMatch.UNAVAILABLE
    }
    if (!observed.isAbsolute || observed.normalize() != observed) {
        return ExistingProjectPathMatch.UNAVAILABLE
    }
    return if (observed == Path.of(expectedRoot.value)) {
        ExistingProjectPathMatch.EXACT
    } else {
        ExistingProjectPathMatch.MISMATCH
    }
}

/**
 * Proof transition: `(Long, Long) -> ExistingProjectImportState`.
 *
 * Establishes whether cached import evidence exists and is at least as recent as the last import
 * attempt. Raw IntelliJ timestamps are permitted only at the live cached-model boundary.
 */
internal fun observeImportState(
    lastSuccessfulImportTimestamp: Long,
    lastImportTimestamp: Long,
): ExistingProjectImportState = when {
    lastSuccessfulImportTimestamp <= 0 -> ExistingProjectImportState.ABSENT
    lastSuccessfulImportTimestamp < lastImportTimestamp -> ExistingProjectImportState.STALE
    else -> ExistingProjectImportState.CURRENT
}

/**
 * Proof transition: `List<ExistingProjectGradleModelObservation> ->
 * ExistingProjectGradleModelState`.
 *
 * Establishes exactly one exact-root cached Gradle model with a ready structure and current
 * successful import. Empty exact-root evidence is unavailable; every ambiguous or incomplete
 * observation is incomplete. Raw platform models remain outside this pure classifier.
 */
internal fun classifyCachedGradleModel(
    observations: List<ExistingProjectGradleModelObservation>,
): ExistingProjectGradleModelState {
    val exact = observations.filter { observation ->
        observation.pathMatch == ExistingProjectPathMatch.EXACT
    }
    if (exact.isEmpty()) return ExistingProjectGradleModelState.UNAVAILABLE
    return if (
        exact.size == 1 &&
        exact.single().structure == ExistingProjectStructureState.READY &&
        exact.single().importState == ExistingProjectImportState.CURRENT
    ) {
        ExistingProjectGradleModelState.COMPLETE
    } else {
        ExistingProjectGradleModelState.INCOMPLETE
    }
}

/** Observation-only adapter over current in-process IntelliJ state. */
internal object LiveExistingProjectObservation : ExistingProjectObservationPort {
    override fun isDisposed(project: Project): Boolean = project.isDisposed

    override fun isOpen(project: Project): Boolean = project.isOpen

    override fun isInitialized(project: Project): Boolean = project.isInitialized

    /**
     * Proof transition: `(Project, CanonicalWorkspaceRoot) -> ExistingProjectRootObservation`.
     *
     * Establishes that the Project's already-published base path is the exact supplied canonical
     * root and returns that existing proof. Missing, relative, non-normalized, malformed, or
     * mismatched paths remain closed observations. Raw path extraction is confined to this live
     * IntelliJ boundary.
     */
    override fun root(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): ExistingProjectRootObservation {
        return when (observeCanonicalPath(project.basePath, expectedRoot)) {
            ExistingProjectPathMatch.EXACT -> ExistingProjectRootObservation.Available(expectedRoot)
            ExistingProjectPathMatch.MISMATCH -> ExistingProjectRootObservation.Mismatch
            ExistingProjectPathMatch.UNAVAILABLE -> ExistingProjectRootObservation.Unavailable
        }
    }

    /**
     * Proof transition: `(Project, CanonicalWorkspaceRoot) ->
     * ExistingProjectGradleModelState`.
     *
     * `COMPLETE` establishes that cached Gradle data contains the exact supplied root with ready
     * structure from the current successful import. `UNAVAILABLE` and `INCOMPLETE` close every
     * missing, mismatched, stale, failed, or partial observation. Raw Gradle model extraction is
     * confined to this live IntelliJ boundary; it performs no preparation, import, link, refresh,
     * or wait.
     */
    override fun gradleModel(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): ExistingProjectGradleModelState {
        val observations = ProjectDataManager.getInstance()
            .getExternalProjectsData(project, ProjectSystemId("GRADLE"))
            .map { info ->
                ExistingProjectGradleModelObservation(
                    pathMatch = observeCanonicalPath(info.externalProjectPath, expectedRoot),
                    structure = if (info.externalProjectStructure?.isReady == true) {
                        ExistingProjectStructureState.READY
                    } else {
                        ExistingProjectStructureState.INCOMPLETE
                    },
                    importState = observeImportState(
                        info.lastSuccessfulImportTimestamp,
                        info.lastImportTimestamp,
                    ),
                )
            }
        return classifyCachedGradleModel(observations)
    }

    /**
     * Proof transition: `Project -> ExistingProjectIndexingState`.
     *
     * Establishes the closed `SMART` or `DUMB` state observed at this instant. Raw dumb-service
     * access is confined to this live IntelliJ boundary; it never waits for smart mode.
     */
    override fun indexing(project: Project): ExistingProjectIndexingState =
        if (DumbService.isDumb(project)) {
            ExistingProjectIndexingState.DUMB
        } else {
            ExistingProjectIndexingState.SMART
        }

    /**
     * Proof transition: `() -> ExistingProjectKotlinMode`.
     *
     * Establishes the closed `K2` or `K1` frontend state of the loaded Kotlin plugin. Raw plugin
     * mode access is confined to this live IntelliJ boundary.
     */
    override fun kotlinMode(): ExistingProjectKotlinMode =
        if (KotlinPluginModeProvider.isK2Mode()) {
            ExistingProjectKotlinMode.K2
        } else {
            ExistingProjectKotlinMode.K1
        }

    /**
     * Proof transition: `() -> ExistingProjectHostIdentityObservation`.
     *
     * Establishes refined IDEA and Kotlin plugin build identities from the loaded host. Missing
     * plugin metadata remains `Unavailable`; malformed identities retain their closed
     * [IdeHostCompatibilityFailure] as `Rejected`. Raw host and plugin text extraction is confined
     * to this live IntelliJ boundary.
     */
    override fun hostIdentity(): ExistingProjectHostIdentityObservation {
        val kotlinPlugin = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.kotlin"))
            ?: return ExistingProjectHostIdentityObservation.Unavailable
        val ideBuild = when (
            val refined = IdeBuildIdentity.parse(
                ApplicationInfo.getInstance().build.asStringWithoutProductCode(),
            )
        ) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return ExistingProjectHostIdentityObservation.Rejected(
                refined.failure,
            )
        }
        val kotlinBuild = when (val refined = KotlinPluginBuildIdentity.parse(kotlinPlugin.version)) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return ExistingProjectHostIdentityObservation.Rejected(
                refined.failure,
            )
        }
        return ExistingProjectHostIdentityObservation.Available(
            ideBuild = ideBuild,
            kotlinPluginBuild = kotlinBuild,
        )
    }
}
