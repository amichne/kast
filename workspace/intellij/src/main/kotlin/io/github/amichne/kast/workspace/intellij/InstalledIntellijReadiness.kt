package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.UnindexedFilesScannerExecutor
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

internal sealed interface InstalledIndexingReadiness {
    data object Ready : InstalledIndexingReadiness

    data class Rejected(
        val failure: InstalledIndexingReadinessFailure,
    ) : InstalledIndexingReadiness
}

internal sealed interface InstalledIndexingReadinessFailure {
    data object Interrupted : InstalledIndexingReadinessFailure
    data object ProjectDisposed : InstalledIndexingReadinessFailure
    data object PlatformObservationUnavailable : InstalledIndexingReadinessFailure
    data object ProjectJvmUnavailable : InstalledIndexingReadinessFailure
    data object ModuleMaterializationUnavailable : InstalledIndexingReadinessFailure
    data object IndexingTimedOut : InstalledIndexingReadinessFailure
}

internal data class InstalledIndexingObservation(
    val smart: Boolean,
    val scannerRunning: Boolean,
    val scannerQueued: Boolean,
    val scannerRevision: Long,
    val projectRootsRevision: InstalledProjectRootsRevision,
    val modulesReady: Boolean,
    val projectJvmReady: Boolean,
)

/** Monotonic IntelliJ roots-model evidence covering module SDK and language-level updates. */
@JvmInline
internal value class InstalledProjectRootsRevision(
    val value: Long,
) {
    init {
        require(value >= 0L)
    }

}

internal enum class InstalledIndexingStability { WAITING, STABLE }

internal enum class InstalledModuleContinuityAction {
    AVAILABLE,
    REMATERIALIZE,
    WAITING,
    FAILED,
}

/**
 * Refines live module observations into one finite recovery allowance.
 *
 * IDEA may apply its persisted JPS model after a completed Gradle import during a warm start. For
 * projects without checked-in JPS files, that delayed replacement temporarily removes the imported
 * module. One exact-workspace rematerialization is admitted. Failure to restore it within [grace],
 * or any later loss of the restored proof, fails closed.
 */
internal class InstalledModuleContinuity(
    private val grace: Duration,
) {
    private val graceNanos = grace.toNanos().also { nanos -> require(nanos > 0) }
    private var state: ModuleContinuityState = ModuleContinuityState.AvailableBeforeRecovery

    fun observe(
        available: Boolean,
        monotonicNanos: Long,
    ): InstalledModuleContinuityAction {
        if (available) {
            if (state is ModuleContinuityState.RecoveryRequested) {
                state = ModuleContinuityState.Restored
            }
            return InstalledModuleContinuityAction.AVAILABLE
        }
        return when (val current = state) {
            ModuleContinuityState.AvailableBeforeRecovery -> {
                state = ModuleContinuityState.RecoveryRequested(monotonicNanos)
                InstalledModuleContinuityAction.REMATERIALIZE
            }
            is ModuleContinuityState.RecoveryRequested -> if (
                monotonicNanos - current.requestedAtNanos >= graceNanos
            ) {
                InstalledModuleContinuityAction.FAILED
            } else {
                InstalledModuleContinuityAction.WAITING
            }
            ModuleContinuityState.Restored -> InstalledModuleContinuityAction.FAILED
        }
    }
}

private sealed interface ModuleContinuityState {
    data object AvailableBeforeRecovery : ModuleContinuityState

    data class RecoveryRequested(
        val requestedAtNanos: Long,
    ) : ModuleContinuityState

    data object Restored : ModuleContinuityState
}

internal enum class InstalledProjectJvmContinuityAction {
    AVAILABLE,
    REASSERT,
    WAITING,
}

/**
 * Refines live project-SDK observations into bounded project-JVM reassertion intervals.
 *
 * Delayed Gradle/JPS workspace-model replacements may rewrite the project SDK more than once after
 * import. Each observed loss admits one reassertion of the already-admitted exact Java home and a
 * finite [grace] interval before another reassertion. The enclosing readiness timeout remains the
 * sole terminal bound while Gradle is still changing the workspace model.
 */
internal class InstalledProjectJvmContinuity(
    private val grace: Duration,
) {
    private val graceNanos = grace.toNanos().also { nanos -> require(nanos > 0) }
    private var state: ProjectJvmContinuityState = ProjectJvmContinuityState.Available

    fun observe(
        available: Boolean,
        monotonicNanos: Long,
    ): InstalledProjectJvmContinuityAction {
        if (available) {
            state = ProjectJvmContinuityState.Available
            return InstalledProjectJvmContinuityAction.AVAILABLE
        }
        return when (val current = state) {
            ProjectJvmContinuityState.Available -> {
                state = ProjectJvmContinuityState.AwaitingReassertion(monotonicNanos)
                InstalledProjectJvmContinuityAction.REASSERT
            }
            is ProjectJvmContinuityState.AwaitingReassertion -> if (
                monotonicNanos - current.requestedAtNanos >= graceNanos
            ) {
                state = ProjectJvmContinuityState.AwaitingReassertion(monotonicNanos)
                InstalledProjectJvmContinuityAction.REASSERT
            } else {
                InstalledProjectJvmContinuityAction.WAITING
            }
        }
    }
}

private sealed interface ProjectJvmContinuityState {
    data object Available : ProjectJvmContinuityState

    data class AwaitingReassertion(
        val requestedAtNanos: Long,
    ) : ProjectJvmContinuityState
}

/**
 * Refines repeated platform observations into a continuous smart, non-executing scanner interval.
 *
 * IDEA 2026.2 derives its public running flag from the presence of a queued task before the task is
 * taken for execution. It may retain both flags after explicitly skipping that task. The queued
 * marker remains diagnostic evidence, while `running && !queued` proves that the current task was
 * taken from the queue and is executing. An executing scanner, scanner revision change, or project
 * roots revision change resets this proof. The roots revision covers Gradle-owned module SDK and
 * language-level writes without requiring those SDKs to equal the sidecar JBR.
 */
internal class InstalledIndexingQuiescence(
    required: Duration,
) {
    private val requiredNanos = required.toNanos().also { nanos -> require(nanos > 0) }
    private var state: IndexingQuiescenceState = IndexingQuiescenceState.Unstable

    fun observe(
        observation: InstalledIndexingObservation,
        monotonicNanos: Long,
    ): InstalledIndexingStability {
        val scannerExecuting = observation.scannerRunning && !observation.scannerQueued
        if (
            !observation.smart ||
            scannerExecuting ||
            !observation.modulesReady ||
            !observation.projectJvmReady
        ) {
            state = IndexingQuiescenceState.Unstable
            return InstalledIndexingStability.WAITING
        }
        val current = state
        if (
            current !is IndexingQuiescenceState.Candidate ||
            current.scannerRevision != observation.scannerRevision ||
            current.projectRootsRevision != observation.projectRootsRevision
        ) {
            state = IndexingQuiescenceState.Candidate(
                monotonicNanos,
                observation.scannerRevision,
                observation.projectRootsRevision,
            )
            return InstalledIndexingStability.WAITING
        }
        return if (monotonicNanos - current.sinceNanos >= requiredNanos) {
            InstalledIndexingStability.STABLE
        } else {
            InstalledIndexingStability.WAITING
        }
    }
}

private sealed interface IndexingQuiescenceState {
    data object Unstable : IndexingQuiescenceState

    data class Candidate(
        val sinceNanos: Long,
        val scannerRevision: Long,
        val projectRootsRevision: InstalledProjectRootsRevision,
    ) : IndexingQuiescenceState
}

internal enum class InstalledModuleAvailability {
    AVAILABLE,
    UNAVAILABLE,
    FAILED,
}

internal enum class InstalledModuleMaterialization {
    AVAILABLE,
    IMPORTED,
    UNAVAILABLE,
    FAILED,
}

internal fun interface InstalledExternalProjectsReader {
    fun read(): Collection<ExternalProjectInfo>
}

internal fun interface InstalledExternalProjectImporter {
    fun import(structure: DataNode<ProjectData>): InstalledExternalProjectImport
}

internal enum class InstalledExternalProjectImport {
    IMPORTED,
    FAILED,
}

internal fun interface InstalledModuleRematerializer {
    fun rematerialize(): InstalledModuleMaterialization
}

/**
 * Proof transition: `Project + AssignedInstalledProjectJvm + InstalledModuleRematerializer ->
 * InstalledIndexingReadiness`.
 *
 * [InstalledIndexingReadiness.Ready] establishes a continuous smart, scanner-idle interval with at
 * least one live IntelliJ module while the exact project SDK resolves the admitted Java home after
 * Gradle model and SDK writes. [InstalledIndexingReadiness.Rejected] retains interruption,
 * timeout, disposal, project-JVM, module-materialization, and platform-observation failures. Live
 * indexing and module state remains inside this bootstrap boundary.
 */
internal fun awaitInstalledIndexingQuiescence(
    project: Project,
    projectJvm: AssignedInstalledProjectJvm,
    moduleRematerializer: InstalledModuleRematerializer,
): InstalledIndexingReadiness {
    var currentProjectJvm = projectJvm
    val dumbService = DumbService.getInstance(project)
    val scanner = UnindexedFilesScannerExecutor.getInstance(project)
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(READINESS_TIMEOUT_MINUTES)
    val quiescence = InstalledIndexingQuiescence(Duration.ofMillis(QUIESCENCE_MILLIS))
    val moduleContinuity = InstalledModuleContinuity(Duration.ofMillis(MODULE_RECOVERY_GRACE_MILLIS))
    val projectJvmContinuity = InstalledProjectJvmContinuity(
        Duration.ofMillis(PROJECT_JVM_RECOVERY_GRACE_MILLIS),
    )
    var previousObservation: InstalledIndexingObservation? = null
    while (true) {
        if (project.isDisposed) return indexingRejected(
            InstalledIndexingReadinessFailure.ProjectDisposed,
        )
        if (System.nanoTime() >= deadline) {
            return indexingRejected(
                when {
                    previousObservation?.projectJvmReady == false ->
                        InstalledIndexingReadinessFailure.ProjectJvmUnavailable
                    previousObservation?.modulesReady == false ->
                        InstalledIndexingReadinessFailure.ModuleMaterializationUnavailable
                    else -> InstalledIndexingReadinessFailure.IndexingTimedOut
                },
            )
        }
        val smart = try {
            !dumbService.isDumb
        } catch (_: RuntimeException) {
            return indexingRejected(
                InstalledIndexingReadinessFailure.PlatformObservationUnavailable,
            )
        }
        val moduleObservation = try {
            ReadAction.nonBlocking<InstalledModuleJvmObservation> {
                val modules = ModuleManager.getInstance(project).modules
                    .filterNot { module -> module.isDisposed }
                InstalledModuleJvmObservation(
                    modulesReady = modules.isNotEmpty(),
                    projectJvmReady = currentProjectJvm.admitsProjectSdk(),
                    projectRootsRevision = InstalledProjectRootsRevision(
                        ProjectRootManager.getInstance(project).modificationCount,
                    ),
                )
            }.executeSynchronously()
        } catch (_: RuntimeException) {
            return indexingRejected(
                InstalledIndexingReadinessFailure.PlatformObservationUnavailable,
            )
        }
        val observation = InstalledIndexingObservation(
            smart = smart,
            scannerRunning = scanner.isRunning.value,
            scannerQueued = scanner.hasQueuedTasks,
            scannerRevision = scanner.modificationTracker.modificationCount,
            projectRootsRevision = moduleObservation.projectRootsRevision,
            modulesReady = moduleObservation.modulesReady,
            projectJvmReady = moduleObservation.projectJvmReady,
        )
        if (observation != previousObservation) {
            READINESS_LOG.info("Kast indexing readiness observation: $observation")
            previousObservation = observation
        }
        val observedAt = System.nanoTime()
        val stability = quiescence.observe(observation, observedAt)
        when (moduleContinuity.observe(moduleObservation.modulesReady, observedAt)) {
            InstalledModuleContinuityAction.AVAILABLE -> {
                when (
                    projectJvmContinuity.observe(
                        moduleObservation.projectJvmReady,
                        observedAt,
                    )
                ) {
                    InstalledProjectJvmContinuityAction.AVAILABLE -> {
                        if (stability == InstalledIndexingStability.STABLE) {
                            return InstalledIndexingReadiness.Ready
                        }
                    }
                    InstalledProjectJvmContinuityAction.REASSERT -> {
                        when (val assignment = currentProjectJvm.reassertAfterImport(project)) {
                            is InstalledProjectJvmAssignment.Assigned -> {
                                currentProjectJvm = assignment.projectJvm
                            }
                            is InstalledProjectJvmAssignment.Rejected ->
                                return indexingRejected(
                                    InstalledIndexingReadinessFailure.ProjectJvmUnavailable,
                                )
                        }
                    }
                    InstalledProjectJvmContinuityAction.WAITING -> Unit
                }
            }
            InstalledModuleContinuityAction.REMATERIALIZE -> {
                when (moduleRematerializer.rematerialize()) {
                    InstalledModuleMaterialization.AVAILABLE,
                    InstalledModuleMaterialization.IMPORTED,
                        -> Unit
                    InstalledModuleMaterialization.UNAVAILABLE,
                    InstalledModuleMaterialization.FAILED,
                        -> return indexingRejected(
                            InstalledIndexingReadinessFailure.ModuleMaterializationUnavailable,
                        )
                }
            }
            InstalledModuleContinuityAction.WAITING -> Unit
            InstalledModuleContinuityAction.FAILED -> return indexingRejected(
                InstalledIndexingReadinessFailure.ModuleMaterializationUnavailable,
            )
        }
        try {
            Thread.sleep(POLL_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return indexingRejected(InstalledIndexingReadinessFailure.Interrupted)
        }
    }
}

private fun indexingRejected(
    failure: InstalledIndexingReadinessFailure,
): InstalledIndexingReadiness.Rejected = InstalledIndexingReadiness.Rejected(failure)

private data class InstalledModuleJvmObservation(
    val modulesReady: Boolean,
    val projectJvmReady: Boolean,
    val projectRootsRevision: InstalledProjectRootsRevision,
)

/**
 * Proof transition: `InstalledModuleAvailability + Path + InstalledExternalProjectsReader +
 * InstalledExternalProjectImporter -> InstalledModuleMaterialization`.
 *
 * [InstalledModuleMaterialization.AVAILABLE] and [InstalledModuleMaterialization.IMPORTED]
 * establish at least one live module or one imported exact-workspace project structure.
 * [InstalledModuleMaterialization.FAILED] closes module observation failure, external-project data
 * lookup failure, malformed external project paths, and platform import failure. Raw IntelliJ
 * project data and `String -> Path` extraction are permitted only inside this bootstrap boundary.
 */
internal fun materializeImportedModules(
    moduleAvailability: InstalledModuleAvailability,
    workspaceRoot: Path,
    externalProjects: InstalledExternalProjectsReader,
    importer: InstalledExternalProjectImporter,
): InstalledModuleMaterialization {
    return when (moduleAvailability) {
        InstalledModuleAvailability.AVAILABLE -> InstalledModuleMaterialization.AVAILABLE
        InstalledModuleAvailability.FAILED -> InstalledModuleMaterialization.FAILED
        InstalledModuleAvailability.UNAVAILABLE -> {
            val exactStructure = try {
                val normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize()
                externalProjects.read()
                    .filter { info ->
                        Path.of(info.externalProjectPath)
                            .toAbsolutePath()
                            .normalize() == normalizedWorkspace
                    }
                    .singleOrNull()
                    ?.externalProjectStructure
            } catch (_: RuntimeException) {
                return InstalledModuleMaterialization.FAILED
            } ?: return InstalledModuleMaterialization.UNAVAILABLE

            try {
                when (importer.import(exactStructure)) {
                    InstalledExternalProjectImport.IMPORTED ->
                        InstalledModuleMaterialization.IMPORTED
                    InstalledExternalProjectImport.FAILED ->
                        InstalledModuleMaterialization.FAILED
                }
            } catch (_: RuntimeException) {
                InstalledModuleMaterialization.FAILED
            }
        }
    }
}

private const val READINESS_TIMEOUT_MINUTES = 5L
private const val QUIESCENCE_MILLIS = 1_500L
private const val MODULE_RECOVERY_GRACE_MILLIS = 5_000L
private const val PROJECT_JVM_RECOVERY_GRACE_MILLIS = 5_000L
private const val POLL_MILLIS = 100L
private val READINESS_LOG = Logger.getInstance("io.github.amichne.kast.indexingReadiness")
