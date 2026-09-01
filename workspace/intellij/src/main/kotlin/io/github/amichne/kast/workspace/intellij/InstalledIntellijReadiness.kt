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
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

internal enum class InstalledIndexingReadiness {
    READY,
    INTERRUPTED,
    TIMED_OUT,
    FAILED,
}

internal data class InstalledIndexingObservation(
    val smart: Boolean,
    val scannerRunning: Boolean,
    val scannerQueued: Boolean,
    val scannerRevision: Long,
    val modulesReady: Boolean,
    val projectJvmReady: Boolean,
)

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
    private var recovery: ModuleRecovery? = null

    fun observe(
        available: Boolean,
        monotonicNanos: Long,
    ): InstalledModuleContinuityAction {
        val current = recovery
        if (available) {
            if (current != null && !current.restored) {
                recovery = current.copy(restored = true)
            }
            return InstalledModuleContinuityAction.AVAILABLE
        }
        if (current == null) {
            recovery = ModuleRecovery(monotonicNanos, restored = false)
            return InstalledModuleContinuityAction.REMATERIALIZE
        }
        if (current.restored || monotonicNanos - current.requestedAtNanos >= graceNanos) {
            return InstalledModuleContinuityAction.FAILED
        }
        return InstalledModuleContinuityAction.WAITING
    }
}

private data class ModuleRecovery(
    val requestedAtNanos: Long,
    val restored: Boolean,
)

internal enum class InstalledProjectJvmContinuityAction {
    AVAILABLE,
    REASSERT,
    WAITING,
    FAILED,
}

/**
 * Refines live module-SDK observations into one finite project-JVM reassertion allowance.
 *
 * A delayed Gradle/JPS workspace-model replacement may clear the project SDK after import. One
 * reassertion of the already-admitted exact Java home is allowed. Failure to restore the proof
 * within [grace], or any later loss of the restored proof, fails closed.
 */
internal class InstalledProjectJvmContinuity(
    private val grace: Duration,
) {
    private val graceNanos = grace.toNanos().also { nanos -> require(nanos > 0) }
    private var recovery: ProjectJvmRecovery? = null

    fun observe(
        available: Boolean,
        monotonicNanos: Long,
    ): InstalledProjectJvmContinuityAction {
        val current = recovery
        if (available) {
            if (current != null && !current.restored) {
                recovery = current.copy(restored = true)
            }
            return InstalledProjectJvmContinuityAction.AVAILABLE
        }
        if (current == null) {
            recovery = ProjectJvmRecovery(monotonicNanos, restored = false)
            return InstalledProjectJvmContinuityAction.REASSERT
        }
        if (current.restored || monotonicNanos - current.requestedAtNanos >= graceNanos) {
            return InstalledProjectJvmContinuityAction.FAILED
        }
        return InstalledProjectJvmContinuityAction.WAITING
    }
}

private data class ProjectJvmRecovery(
    val requestedAtNanos: Long,
    val restored: Boolean,
)

/**
 * Refines repeated platform observations into a continuous smart, non-executing scanner interval.
 *
 * IDEA 2026.2 derives its public running flag from the presence of a queued task before the task is
 * taken for execution. It may retain both flags after explicitly skipping that task. The queued
 * marker remains diagnostic evidence, while `running && !queued` proves that the current task was
 * taken from the queue and is executing. An executing scanner or revision change resets this proof.
 */
internal class InstalledIndexingQuiescence(
    required: Duration,
) {
    private val requiredNanos = required.toNanos().also { nanos -> require(nanos > 0) }
    private var candidate: StableIndexingCandidate? = null

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
            candidate = null
            return InstalledIndexingStability.WAITING
        }
        val current = candidate
        if (current == null || current.scannerRevision != observation.scannerRevision) {
            candidate = StableIndexingCandidate(monotonicNanos, observation.scannerRevision)
            return InstalledIndexingStability.WAITING
        }
        return if (monotonicNanos - current.sinceNanos >= requiredNanos) {
            InstalledIndexingStability.STABLE
        } else {
            InstalledIndexingStability.WAITING
        }
    }
}

private data class StableIndexingCandidate(
    val sinceNanos: Long,
    val scannerRevision: Long,
)

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
 * [InstalledIndexingReadiness.READY] establishes a continuous smart, scanner-idle interval with at
 * least one live IntelliJ module whose SDK resolves the admitted Java home after Gradle model and
 * SDK writes. Other variants close
 * interruption, timeout, disposal, and platform failure. Live indexing and module state remains
 * inside this bootstrap boundary.
 */
internal fun awaitInstalledIndexingQuiescence(
    project: Project,
    projectJvm: AssignedInstalledProjectJvm,
    moduleRematerializer: InstalledModuleRematerializer,
): InstalledIndexingReadiness {
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
        if (project.isDisposed) return InstalledIndexingReadiness.FAILED
        if (System.nanoTime() >= deadline) return InstalledIndexingReadiness.TIMED_OUT
        try {
            dumbService.waitForSmartMode()
        } catch (_: RuntimeException) {
            return InstalledIndexingReadiness.FAILED
        }
        val moduleObservation = try {
            ReadAction.nonBlocking<InstalledModuleJvmObservation> {
                val modules = ModuleManager.getInstance(project).modules
                    .filterNot { module -> module.isDisposed }
                InstalledModuleJvmObservation(
                    modulesReady = modules.isNotEmpty(),
                    projectJvmReady = modules.isNotEmpty() && modules.all(projectJvm::admits),
                )
            }.executeSynchronously()
        } catch (_: RuntimeException) {
            return InstalledIndexingReadiness.FAILED
        }
        val observation = InstalledIndexingObservation(
            smart = !dumbService.isDumb,
            scannerRunning = scanner.isRunning.value,
            scannerQueued = scanner.hasQueuedTasks,
            scannerRevision = scanner.modificationTracker.modificationCount,
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
                            return InstalledIndexingReadiness.READY
                        }
                    }
                    InstalledProjectJvmContinuityAction.REASSERT -> {
                        when (projectJvm.reassertAfterImport(project)) {
                            is InstalledProjectJvmAssignment.Assigned -> Unit
                            is InstalledProjectJvmAssignment.Rejected ->
                                return InstalledIndexingReadiness.FAILED
                        }
                    }
                    InstalledProjectJvmContinuityAction.WAITING -> Unit
                    InstalledProjectJvmContinuityAction.FAILED ->
                        return InstalledIndexingReadiness.FAILED
                }
            }
            InstalledModuleContinuityAction.REMATERIALIZE -> {
                when (moduleRematerializer.rematerialize()) {
                    InstalledModuleMaterialization.AVAILABLE,
                    InstalledModuleMaterialization.IMPORTED,
                        -> Unit
                    InstalledModuleMaterialization.UNAVAILABLE,
                    InstalledModuleMaterialization.FAILED,
                        -> return InstalledIndexingReadiness.FAILED
                }
            }
            InstalledModuleContinuityAction.WAITING -> Unit
            InstalledModuleContinuityAction.FAILED -> return InstalledIndexingReadiness.FAILED
        }
        try {
            Thread.sleep(POLL_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return InstalledIndexingReadiness.INTERRUPTED
        }
    }
}

private data class InstalledModuleJvmObservation(
    val modulesReady: Boolean,
    val projectJvmReady: Boolean,
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
