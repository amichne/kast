package io.github.amichne.kast.workspace.intellij

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.startup.StartupManager
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefresh
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshFailure
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSystemSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val GRADLE_IMPORT_TIMEOUT_MINUTES = 5L
private const val STARTUP_POLL_MILLIS = 100L

enum class InstalledIntellijWorkspaceFailure {
    PROJECT_OPEN_FAILED,
    STARTUP_FAILED,
    GRADLE_JVM_UNAVAILABLE,
    PROJECT_JVM_UNAVAILABLE,
    GRADLE_IMPORT_FAILED,
    GRADLE_IMPORT_TIMED_OUT,
    INDEXING_INTERRUPTED,
    MODEL_UNAVAILABLE,
    MODEL_ROOT_UNAVAILABLE,
    MODEL_EXTERNAL_PROJECT_UNAVAILABLE,
    MODEL_EXTERNAL_PROJECT_INCOMPLETE,
    MODEL_SOURCE_ROOTS_UNAVAILABLE,
    MODEL_SOURCE_STATE_UNAVAILABLE,
    MODEL_SEMANTIC_INPUT_INCOMPLETE,
    MODEL_SEMANTIC_PROJECT_PATH_INVALID,
    MODEL_SEMANTIC_SOURCE_ROOT_INVALID,
    MODEL_SEMANTIC_MODULE_INVALID,
    MODEL_STATE_IDENTITY_REJECTED,
}

/** Ordered installed workspace bootstrap boundaries visible to the owning runtime. */
enum class InstalledIntellijWorkspaceBootstrapPhase {
    PROJECT_IMPORT,
    INDEXING,
    MODEL_CAPTURE,
}

/** Explicit effect boundary for observing installed workspace bootstrap progress. */
fun interface InstalledIntellijWorkspaceBootstrapObserver {
    fun observe(phase: InstalledIntellijWorkspaceBootstrapPhase)
}

/** Detached complete model proof from one exact IntelliJ-opened Gradle workspace. */
class InstalledIntellijWorkspaceModel internal constructor(
    val capture: InstalledGradleModelCapture,
    private val project: Project,
    private val projectJvm: AssignedInstalledProjectJvm,
    private val moduleRematerializer: InstalledModuleRematerializer,
) {
    /**
     * Proof transition: `InstalledIntellijWorkspaceModel -> Refinement<WorkspaceStateIdentity,
     * InstalledGradleModelCaptureFailure>`.
     *
     * Establishes a continuous smart, scanner-idle interval before capturing current source
     * content under the same imported Gradle identity. The closed failure retains indexing and
     * capture rejection. The live IntelliJ project remains inside this capability boundary.
     */
    fun captureCurrentSemanticIdentity(): io.github.amichne.kast.kernel.Refinement<
        io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity,
        InstalledGradleModelCaptureFailure,
        > = when (awaitInstalledIndexingQuiescence(project, projectJvm, moduleRematerializer)) {
        InstalledIndexingReadiness.READY -> capture.captureCurrentSemanticIdentity()
        InstalledIndexingReadiness.INTERRUPTED,
        InstalledIndexingReadiness.TIMED_OUT,
        InstalledIndexingReadiness.FAILED,
            -> io.github.amichne.kast.kernel.Refinement.Rejected(
                InstalledGradleModelCaptureFailure.INDEXING_UNAVAILABLE,
            )
    }

    /** Refreshes the exact admitted roots, then proves installed indexing has become quiescent. */
    fun awaitIndexReadinessAfter(
        refresh: WorkspaceIndexRefreshOperations,
    ): WorkspaceIndexRefreshOperations = WorkspaceIndexRefreshOperations { workspace ->
        when (val physical = refresh.refresh(workspace)) {
            is WorkspaceIndexRefresh.Rejected -> physical
            WorkspaceIndexRefresh.Refreshed -> when (
                awaitInstalledIndexingQuiescence(project, projectJvm, moduleRematerializer)
            ) {
                InstalledIndexingReadiness.READY -> WorkspaceIndexRefresh.Refreshed
                InstalledIndexingReadiness.INTERRUPTED -> WorkspaceIndexRefresh.Rejected(
                    WorkspaceIndexRefreshFailure.INDEXING_INTERRUPTED,
                )
                InstalledIndexingReadiness.TIMED_OUT -> WorkspaceIndexRefresh.Rejected(
                    WorkspaceIndexRefreshFailure.INDEXING_TIMED_OUT,
                )
                InstalledIndexingReadiness.FAILED -> WorkspaceIndexRefresh.Rejected(
                    WorkspaceIndexRefreshFailure.INDEXING_FAILED,
                )
            }
        }
    }
}

sealed interface InstalledIntellijWorkspaceOpening {
    data class Opened(
        val model: InstalledIntellijWorkspaceModel,
    ) : InstalledIntellijWorkspaceOpening

    data class Rejected(
        val failure: InstalledIntellijWorkspaceFailure,
    ) : InstalledIntellijWorkspaceOpening
}

/** Sole installed IntelliJ project-open, Gradle-import, and model-capture boundary. */
object InstalledIntellijWorkspace {
    /**
     * Proof transition: `Path -> InstalledIntellijWorkspaceOpening`.
     *
     * [InstalledIntellijWorkspaceOpening.Opened] establishes that IntelliJ opened the exact path,
     * completed one observed project-open import or explicit Gradle link, reached smart mode, and
     * detached one complete Gradle model. [InstalledIntellijWorkspaceFailure] closes every expected
     * bootstrap failure. The live project and Gradle objects remain inside this adapter and the
     * IntelliJ project lifecycle.
     */
    fun open(
        workspaceRoot: Path,
        observer: InstalledIntellijWorkspaceBootstrapObserver =
            InstalledIntellijWorkspaceBootstrapObserver {},
    ): InstalledIntellijWorkspaceOpening {
        observer.observe(InstalledIntellijWorkspaceBootstrapPhase.PROJECT_IMPORT)
        GradleSystemSettings.getInstance().isDownloadSources = false
        val gradleJvm = when (val admission = InstalledGradleJvm.admit(
            System.getProperty("java.home")
            ?: return rejected(InstalledIntellijWorkspaceFailure.GRADLE_JVM_UNAVAILABLE),
            System.getenv("JAVA_HOME"),
        )) {
            is InstalledGradleJvmAdmission.Admitted -> admission.jvm
            is InstalledGradleJvmAdmission.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_JVM_UNAVAILABLE,
            )
        }
        val importObserver = InstalledGradleImportObserver(workspaceRoot)
        val notificationManager = ExternalSystemProgressNotificationManager.getInstance()
        if (!notificationManager.addNotificationListener(importObserver)) {
            return rejected(InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED)
        }
        return try {
            openObserved(workspaceRoot, gradleJvm, importObserver, observer)
        } finally {
            notificationManager.removeNotificationListener(importObserver)
        }
    }

    private fun openObserved(
        workspaceRoot: Path,
        gradleJvm: InstalledGradleJvm,
        importObserver: InstalledGradleImportObserver,
        observer: InstalledIntellijWorkspaceBootstrapObserver,
    ): InstalledIntellijWorkspaceOpening {
        val preparation = InstalledProjectOpenPreparation(workspaceRoot, gradleJvm)
        val project = try {
            ProjectManagerEx.getInstanceEx().openProject(
                workspaceRoot,
                openProjectTask(preparation),
            )
        } catch (_: RuntimeException) {
            null
        } ?: return when (val state = preparation.observe()) {
            is InstalledProjectOpenPreparationState.Rejected -> rejected(
                state.failure.workspaceFailure(),
            )
            InstalledProjectOpenPreparationState.Pending,
            is InstalledProjectOpenPreparationState.Prepared,
                -> rejected(InstalledIntellijWorkspaceFailure.PROJECT_OPEN_FAILED)
        }
        val prepared = when (val state = preparation.observe()) {
            is InstalledProjectOpenPreparationState.Prepared -> state
            is InstalledProjectOpenPreparationState.Rejected -> return rejected(
                state.failure.workspaceFailure(),
            )
            InstalledProjectOpenPreparationState.Pending -> return rejected(
                InstalledIntellijWorkspaceFailure.PROJECT_OPEN_FAILED,
            )
        }

        when (awaitStartup(project)) {
            FutureCompletion.COMPLETED -> Unit
            FutureCompletion.INTERRUPTED -> return rejected(
                InstalledIntellijWorkspaceFailure.INDEXING_INTERRUPTED,
            )
            FutureCompletion.TIMED_OUT,
            FutureCompletion.FAILED,
                -> return rejected(InstalledIntellijWorkspaceFailure.STARTUP_FAILED)
        }
        if (prepared.importOperation is InstalledGradleImportOperation.AwaitLinked) {
            importObserver.closeProjectOpenAdmission()
        }

        val imported = CompletableFuture<Void>()
        val closedImported = imported.closedImportOutcome()
        val specification = ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
            .withCallback(imported)
        val importCompletion = try {
            when (prepared.importOperation) {
                InstalledGradleImportOperation.AwaitLinked -> importObserver.completion
                InstalledGradleImportOperation.LinkUnlinked -> {
                    val settings = GradleProjectSettings(workspaceRoot.toString()).apply {
                        this.gradleJvm = gradleJvm.projectSettingsSelector()
                    }
                    ExternalSystemUtil.linkExternalProject(settings, specification)
                    closedImported
                }
            }
        } catch (_: RuntimeException) {
            return rejected(InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED)
        }
        when (awaitImport(importCompletion)) {
            InstalledGradleImportWait.COMPLETED -> Unit
            InstalledGradleImportWait.INTERRUPTED -> return rejected(
                InstalledIntellijWorkspaceFailure.INDEXING_INTERRUPTED,
            )
            InstalledGradleImportWait.TIMED_OUT -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_TIMED_OUT,
            )
            InstalledGradleImportWait.CANCELLED,
            InstalledGradleImportWait.FAILED,
                -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED,
            )
        }
        when (materializeImportedModules(project, workspaceRoot)) {
            InstalledModuleMaterialization.AVAILABLE,
            InstalledModuleMaterialization.IMPORTED,
                -> Unit
            InstalledModuleMaterialization.UNAVAILABLE,
            InstalledModuleMaterialization.FAILED,
                -> return rejected(InstalledIntellijWorkspaceFailure.MODEL_UNAVAILABLE)
        }
        val assignedProjectJvm = when (val assignment = prepared.projectJvm.reassertAfterImport(project)) {
            is InstalledProjectJvmAssignment.Assigned -> assignment.projectJvm
            is InstalledProjectJvmAssignment.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE,
            )
        }
        val moduleRematerializer = InstalledModuleRematerializer {
            materializeImportedModules(project, workspaceRoot)
        }
        observer.observe(InstalledIntellijWorkspaceBootstrapPhase.INDEXING)
        when (awaitInstalledIndexingQuiescence(project, assignedProjectJvm, moduleRematerializer)) {
            InstalledIndexingReadiness.READY -> Unit
            InstalledIndexingReadiness.INTERRUPTED -> return rejected(
                InstalledIntellijWorkspaceFailure.INDEXING_INTERRUPTED,
            )
            InstalledIndexingReadiness.TIMED_OUT,
            InstalledIndexingReadiness.FAILED,
                -> return rejected(InstalledIntellijWorkspaceFailure.MODEL_UNAVAILABLE)
        }
        observer.observe(InstalledIntellijWorkspaceBootstrapPhase.MODEL_CAPTURE)
        val capture = when (val captured = captureInstalledGradleModel(project, workspaceRoot)) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> captured.value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> return rejected(
                captured.failure.workspaceFailure(),
            )
        }
        return InstalledIntellijWorkspaceOpening.Opened(
            InstalledIntellijWorkspaceModel(
                capture,
                project,
                assignedProjectJvm,
                moduleRematerializer,
            ),
        )
    }

    private fun openProjectTask(
        preparation: InstalledProjectOpenPreparation,
    ): OpenProjectTask = OpenProjectTask.build().copy(
        isRefreshVfsNeeded = true,
        runConfigurators = true,
        runConversionBeforeOpen = false,
        preloadServices = true,
        beforeOpen = { project ->
            preparation.prepare(project) is InstalledProjectOpenPreparationState.Prepared
        },
    )

    private fun awaitStartup(
        project: com.intellij.openapi.project.Project,
    ): FutureCompletion {
        val startup = StartupManager.getInstance(project)
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(GRADLE_IMPORT_TIMEOUT_MINUTES)
        while (!startup.postStartupActivityPassed()) {
            if (project.isDisposed) return FutureCompletion.FAILED
            if (System.nanoTime() >= deadline) return FutureCompletion.TIMED_OUT
            try {
                Thread.sleep(STARTUP_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return FutureCompletion.INTERRUPTED
            }
        }
        return FutureCompletion.COMPLETED
    }

    /**
     * Proof transition: `CompletableFuture<InstalledGradleImportOutcome> ->
     * InstalledGradleImportWait`.
     *
     * Establishes one closed terminal import observation within the installed timeout.
     * [InstalledGradleImportWait] closes cancellation, timeout, interruption, and unexpected
     * future failure. The future remains inside the installed import boundary.
     */
    private fun awaitImport(
        future: CompletableFuture<InstalledGradleImportOutcome>,
    ): InstalledGradleImportWait = try {
        when (future.get(GRADLE_IMPORT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            InstalledGradleImportOutcome.Completed -> InstalledGradleImportWait.COMPLETED
            InstalledGradleImportOutcome.Failed -> InstalledGradleImportWait.FAILED
            InstalledGradleImportOutcome.Cancelled -> InstalledGradleImportWait.CANCELLED
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        InstalledGradleImportWait.INTERRUPTED
    } catch (_: TimeoutException) {
        InstalledGradleImportWait.TIMED_OUT
    } catch (_: ExecutionException) {
        InstalledGradleImportWait.FAILED
    } catch (_: java.util.concurrent.CancellationException) {
        InstalledGradleImportWait.CANCELLED
    }

    /**
     * Proof transition: `Project + Path -> InstalledModuleMaterialization`.
     *
     * Available or imported establishes at least one live IntelliJ module for the exact complete
     * Gradle project data. Other variants close absent cached data and platform import failure.
     * Live project data remains inside this installed bootstrap boundary.
     */
    private fun materializeImportedModules(
        project: com.intellij.openapi.project.Project,
        workspaceRoot: Path,
    ): InstalledModuleMaterialization {
        val moduleAvailability = try {
            if (
                ReadAction.nonBlocking<Boolean> {
                    ModuleManager.getInstance(project).modules.any { module -> !module.isDisposed }
                }.executeSynchronously()
            ) {
                InstalledModuleAvailability.AVAILABLE
            } else {
                InstalledModuleAvailability.UNAVAILABLE
            }
        } catch (_: RuntimeException) {
            InstalledModuleAvailability.FAILED
        }
        return materializeImportedModules(
            moduleAvailability,
            workspaceRoot,
            InstalledExternalProjectsReader {
                ProjectDataManager.getInstance()
                    .getExternalProjectsData(project, GradleConstants.SYSTEM_ID)
            },
            InstalledExternalProjectImporter { structure ->
                try {
                    val dataManager = ProjectDataManager.getInstance()
                    dataManager.ensureTheDataIsReadyToUse(structure)
                    dataManager.importData(structure, project)
                    InstalledExternalProjectImport.IMPORTED
                } catch (_: RuntimeException) {
                    InstalledExternalProjectImport.FAILED
                }
            },
        )
    }
}

private fun InstalledGradleModelCaptureFailure.workspaceFailure(): InstalledIntellijWorkspaceFailure =
    when (this) {
        InstalledGradleModelCaptureFailure.ROOT_UNAVAILABLE ->
            InstalledIntellijWorkspaceFailure.MODEL_ROOT_UNAVAILABLE
        InstalledGradleModelCaptureFailure.EXTERNAL_PROJECT_UNAVAILABLE ->
            InstalledIntellijWorkspaceFailure.MODEL_EXTERNAL_PROJECT_UNAVAILABLE
        InstalledGradleModelCaptureFailure.EXTERNAL_PROJECT_INCOMPLETE ->
            InstalledIntellijWorkspaceFailure.MODEL_EXTERNAL_PROJECT_INCOMPLETE
        InstalledGradleModelCaptureFailure.SOURCE_ROOTS_UNAVAILABLE ->
            InstalledIntellijWorkspaceFailure.MODEL_SOURCE_ROOTS_UNAVAILABLE
        InstalledGradleModelCaptureFailure.SOURCE_STATE_UNAVAILABLE ->
            InstalledIntellijWorkspaceFailure.MODEL_SOURCE_STATE_UNAVAILABLE
        InstalledGradleModelCaptureFailure.INDEXING_UNAVAILABLE ->
            InstalledIntellijWorkspaceFailure.MODEL_UNAVAILABLE
        InstalledGradleModelCaptureFailure.SEMANTIC_INPUT_INCOMPLETE ->
            InstalledIntellijWorkspaceFailure.MODEL_SEMANTIC_INPUT_INCOMPLETE
        InstalledGradleModelCaptureFailure.SEMANTIC_PROJECT_PATH_INVALID ->
            InstalledIntellijWorkspaceFailure.MODEL_SEMANTIC_PROJECT_PATH_INVALID
        InstalledGradleModelCaptureFailure.SEMANTIC_SOURCE_ROOT_INVALID ->
            InstalledIntellijWorkspaceFailure.MODEL_SEMANTIC_SOURCE_ROOT_INVALID
        InstalledGradleModelCaptureFailure.SEMANTIC_MODULE_INVALID ->
            InstalledIntellijWorkspaceFailure.MODEL_SEMANTIC_MODULE_INVALID
        InstalledGradleModelCaptureFailure.STATE_IDENTITY_REJECTED ->
            InstalledIntellijWorkspaceFailure.MODEL_STATE_IDENTITY_REJECTED
    }

private enum class FutureCompletion {
    COMPLETED,
    INTERRUPTED,
    TIMED_OUT,
    FAILED,
}

private enum class InstalledGradleImportWait {
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    INTERRUPTED,
}

private fun InstalledProjectOpenPreparationFailure.workspaceFailure():
    InstalledIntellijWorkspaceFailure = when (this) {
        InstalledProjectOpenPreparationFailure.PROJECT_JVM_REJECTED ->
            InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE
        InstalledProjectOpenPreparationFailure.GRADLE_SETTINGS_REJECTED ->
            InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED
    }

private fun rejected(
    failure: InstalledIntellijWorkspaceFailure,
): InstalledIntellijWorkspaceOpening.Rejected = InstalledIntellijWorkspaceOpening.Rejected(failure)
