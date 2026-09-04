package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionReport
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.kernel.Refinement
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTrackerSettings
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.startup.StartupManager
import io.github.amichne.kast.workspace.contract.CanonicalSemanticProjectRoot
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefresh
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshFailure
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
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
    PROJECT_STORE_OVERLAPS_WORKSPACE,
    PROJECT_STORE_CREATION_FAILED,
    PROJECT_STORE_IDENTITY_REJECTED,
    PROJECT_STORE_EXCLUSION_DISCOVERY_FAILED,
    PROJECT_STORE_CONFIGURATION_WRITE_FAILED,
    INDEX_BOOTSTRAP_MODULE_UNAVAILABLE,
    INDEX_BOOTSTRAP_EXCLUSION_POLICY_MISMATCH,
    INDEX_BOOTSTRAP_CONTENT_ROOT_MISMATCH,
    INDEX_BOOTSTRAP_EXCLUSION_ROOTS_MISMATCH,
    INDEX_BOOTSTRAP_PLATFORM_OBSERVATION_FAILED,
    INDEX_BOOTSTRAP_RETIREMENT_IDENTITY_LOST,
    INDEX_BOOTSTRAP_RETIREMENT_FAILED,
    INDEX_BOOTSTRAP_IMPORTED_MODULES_UNAVAILABLE,
    INDEX_BOOTSTRAP_EXCLUSION_ROOT_UNAVAILABLE,
    INDEX_BOOTSTRAP_EXCLUSION_NOT_PRESERVED,
    INDEX_BOOTSTRAP_SOURCE_ROOT_NOT_ADMITTED,
    PROJECT_OPEN_FAILED,
    STARTUP_FAILED,
    GRADLE_JVM_UNAVAILABLE,
    PROJECT_JVM_UNAVAILABLE,
    PLATFORM_LINKAGE_INVALID,
    GRADLE_IMPORT_FAILED,
    GRADLE_PROJECT_POLICY_INVALID,
    GRADLE_JVM_CONFIGURATION_INVALID,
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
    GRADLE_JVM_SELECTION,
    PROJECT_IMPORT,
    INDEXING,
    MODEL_CAPTURE,
}

/** Explicit effect boundary for observing installed workspace bootstrap progress. */
fun interface InstalledIntellijWorkspaceBootstrapObserver {
    fun observe(phase: InstalledIntellijWorkspaceBootstrapPhase)
    fun observeGradleJvm(report: GradleJvmSelectionReport) {}
}

/** Detached complete model proof from one exact IntelliJ-opened Gradle workspace. */
class InstalledIntellijWorkspaceModel internal constructor(
    val capture: InstalledGradleModelCapture,
    val semanticProjectRoot: CanonicalSemanticProjectRoot,
    private val project: Project,
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
        > = when (awaitInstalledIndexingQuiescence(project, moduleRematerializer)) {
        InstalledIndexingReadiness.Ready -> capture.captureCurrentSemanticIdentity()
        is InstalledIndexingReadiness.Rejected -> io.github.amichne.kast.kernel.Refinement.Rejected(
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
                val readiness =
                    awaitInstalledIndexingQuiescence(project, moduleRematerializer)
            ) {
                InstalledIndexingReadiness.Ready -> WorkspaceIndexRefresh.Refreshed
                is InstalledIndexingReadiness.Rejected -> WorkspaceIndexRefresh.Rejected(
                    when (readiness.failure) {
                        InstalledIndexingReadinessFailure.Interrupted ->
                            WorkspaceIndexRefreshFailure.INDEXING_INTERRUPTED
                        InstalledIndexingReadinessFailure.IndexingTimedOut ->
                            WorkspaceIndexRefreshFailure.INDEXING_TIMED_OUT
                        InstalledIndexingReadinessFailure.ModuleMaterializationUnavailable,
                        InstalledIndexingReadinessFailure.PlatformLinkageInvalid,
                        InstalledIndexingReadinessFailure.PlatformObservationUnavailable,
                        InstalledIndexingReadinessFailure.ProjectDisposed,
                            -> WorkspaceIndexRefreshFailure.INDEXING_FAILED
                    },
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

/** Closed projection from the indexing wait into the installed workspace-opening boundary. */
internal sealed interface InstalledWorkspaceIndexingAdmission {
    data object Ready : InstalledWorkspaceIndexingAdmission

    data class Rejected(
        val failure: InstalledIntellijWorkspaceFailure,
    ) : InstalledWorkspaceIndexingAdmission
}

/**
 * Proof transition: `InstalledIndexingReadiness -> InstalledWorkspaceIndexingAdmission`.
 *
 * Retains the finite indexing cause at the workspace-opening boundary instead of using nullable
 * or exceptional control flow.
 */
internal fun InstalledIndexingReadiness.workspaceOpeningAdmission():
    InstalledWorkspaceIndexingAdmission = when (this) {
        InstalledIndexingReadiness.Ready -> InstalledWorkspaceIndexingAdmission.Ready
        is InstalledIndexingReadiness.Rejected -> InstalledWorkspaceIndexingAdmission.Rejected(
            when (failure) {
                InstalledIndexingReadinessFailure.Interrupted ->
                    InstalledIntellijWorkspaceFailure.INDEXING_INTERRUPTED
                InstalledIndexingReadinessFailure.PlatformLinkageInvalid ->
                    InstalledIntellijWorkspaceFailure.PLATFORM_LINKAGE_INVALID
                InstalledIndexingReadinessFailure.IndexingTimedOut,
                InstalledIndexingReadinessFailure.ModuleMaterializationUnavailable,
                InstalledIndexingReadinessFailure.PlatformObservationUnavailable,
                InstalledIndexingReadinessFailure.ProjectDisposed,
                    -> InstalledIntellijWorkspaceFailure.STARTUP_FAILED
            },
        )
    }

/** Sole installed IntelliJ project-open, Gradle-import, and model-capture boundary. */
object InstalledIntellijWorkspace {
    /**
     * Proof transition: `(CanonicalWorkspaceRoot, Path) -> InstalledIntellijWorkspaceOpening`.
     *
     * [InstalledIntellijWorkspaceOpening.Opened] establishes that IntelliJ opened a fresh project
     * store beneath [runtimeStateDirectory], disjoint from [workspaceRoot], applied the installed
     * Gradle policy, completed one explicit Gradle link, reached smart mode, and detached one
     * complete Gradle model. Workspace `.idea` state is neither opened nor reused.
     * [InstalledIntellijWorkspaceFailure] closes every expected bootstrap failure. The live project
     * and Gradle objects remain inside this adapter and the IntelliJ project lifecycle.
     */
    fun open(
        workspaceRoot: CanonicalWorkspaceRoot,
        runtimeStateDirectory: Path,
        observer: InstalledIntellijWorkspaceBootstrapObserver =
            InstalledIntellijWorkspaceBootstrapObserver {},
    ): InstalledIntellijWorkspaceOpening {
        val workspacePath = Path.of(workspaceRoot.value)
        val importEnvironment = when (val admission = GradleImportEnvironment.admit(
            System.getenv(GradleImportEnvironment.VARIABLES_SETTING).orEmpty(),
            System.getenv(GradleImportEnvironment.PATH_SETTING).orEmpty(),
            System.getenv(),
        )) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return rejected(InstalledIntellijWorkspaceFailure.GRADLE_JVM_CONFIGURATION_INVALID)
        }
        val projectJvmAuthority = projectGradleJvmAuthority(workspacePath)
        if (projectJvmAuthority == ProjectGradleJvmAuthority.Rejected) {
            observer.observeGradleJvm(InstalledGradleJvmSelection.Rejected(
                InstalledGradleJvmSelectionFailure.REPOSITORY_JAVA_HOME_INVALID,
            ).report)
            return rejected(InstalledIntellijWorkspaceFailure.GRADLE_JVM_CONFIGURATION_INVALID)
        }
        val projectStore = when (
            val prepared = InstalledSemanticProjectStore.prepare(
                workspaceRoot,
                runtimeStateDirectory,
            )
        ) {
            is InstalledSemanticProjectStorePreparation.Prepared -> prepared.store
            is InstalledSemanticProjectStorePreparation.Rejected -> return rejected(
                prepared.failure.workspaceFailure(),
            )
        }
        GradleSystemSettings.getInstance().isDownloadSources = false
        val sidecarJvm = when (val admission = InstalledSidecarJvm.admit(
            System.getProperty("java.home")
            ?: return rejected(InstalledIntellijWorkspaceFailure.GRADLE_JVM_UNAVAILABLE),
            System.getenv("JAVA_HOME"),
        )) {
            is InstalledSidecarJvmAdmission.Admitted -> admission.jvm
            is InstalledSidecarJvmAdmission.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_JVM_UNAVAILABLE,
            )
        }
        return openObserved(workspacePath, projectStore, sidecarJvm, projectJvmAuthority, importEnvironment, observer)
    }

    private fun openObserved(
        workspaceRoot: Path,
        projectStore: InstalledSemanticProjectStore,
        sidecarJvm: InstalledSidecarJvm,
        projectJvmAuthority: ProjectGradleJvmAuthority,
        importEnvironment: GradleImportEnvironment,
        observer: InstalledIntellijWorkspaceBootstrapObserver,
    ): InstalledIntellijWorkspaceOpening {
        val preparation = InstalledProjectOpenPreparation(BootstrapProjectJvm.from(sidecarJvm))
        val project = try {
            ProjectManagerEx.getInstanceEx().openProject(
                projectStore.path,
                installedProjectOpenTask(preparation, projectStore.indexBootstrap),
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

        val activeIndexBootstrap = when (
            val activation = projectStore.indexBootstrap.activate(project)
        ) {
            is InstalledIndexBootstrapActivation.Active -> activation.bootstrap
            is InstalledIndexBootstrapActivation.Rejected -> return rejected(
                activation.failure.workspaceFailure(),
            )
        }
        INDEX_BOOTSTRAP_LOG.info(
            "event=activated excludedDirectoryCount=" +
                projectStore.indexBootstrap.excludedDirectoryCount,
        )

        when (awaitStartup(project)) {
            FutureCompletion.COMPLETED -> Unit
            FutureCompletion.INTERRUPTED -> return rejected(
                InstalledIntellijWorkspaceFailure.INDEXING_INTERRUPTED,
            )
            FutureCompletion.TIMED_OUT,
            FutureCompletion.FAILED,
                -> return rejected(InstalledIntellijWorkspaceFailure.STARTUP_FAILED)
        }
        val gradleSettings = when (val policy = applyInstalledGradleProjectPolicy(project)) {
            is InstalledGradleProjectPolicyApplication.Applied -> policy.settings
            InstalledGradleProjectPolicyApplication.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_PROJECT_POLICY_INVALID,
            )
        }
        val linkPresence = when (val resolution = try {
            linkedGradleProject(gradleSettings, workspaceRoot)
        } catch (_: RuntimeException) {
            InstalledGradleLinkPresenceResolution.Rejected
        }) {
            is InstalledGradleLinkPresenceResolution.Resolved -> resolution.presence
            InstalledGradleLinkPresenceResolution.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED,
            )
        }
        val linkedProjectSettings = when (linkPresence) {
            is InstalledGradleLinkPresence.Linked -> linkPresence.settings
            is InstalledGradleLinkPresence.Unlinked -> linkPresence.settings
        }
        observer.observe(InstalledIntellijWorkspaceBootstrapPhase.GRADLE_JVM_SELECTION)
        val selection = selectInstalledGradleJvm(project, linkedProjectSettings, sidecarJvm, projectJvmAuthority)
        observer.observeGradleJvm(selection.report)
        val selectedGradleJvm = when (selection) {
            is InstalledGradleJvmSelection.Selected -> selection.jvm
            is InstalledGradleJvmSelection.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_JVM_UNAVAILABLE,
            )
        }
        val importOperation = when (
            val application = linkPresence.applyImportJvm(selectedGradleJvm)
        ) {
            is InstalledGradleImportApplication.Applied -> application.operation
            InstalledGradleImportApplication.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED,
            )
        }

        observer.observe(InstalledIntellijWorkspaceBootstrapPhase.PROJECT_IMPORT)
        val imported = CompletableFuture<Void>()
        val closedImported = imported.closedImportOutcome()
        val specification = ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
            .withCallback(imported)
        val importCompletion = try {
            when (importOperation) {
                InstalledGradleImportOperation.RefreshLinked -> {
                    ExternalSystemUtil.refreshProject(workspaceRoot.toString(), specification)
                    closedImported
                }
                InstalledGradleImportOperation.LinkUnlinked -> {
                    val settings = (linkPresence as InstalledGradleLinkPresence.Unlinked).settings
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
            InstalledGradleImportWait.INVALID_JVM_CONFIGURATION -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_JVM_CONFIGURATION_INVALID,
            )
            InstalledGradleImportWait.CANCELLED,
            InstalledGradleImportWait.FAILED,
                -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED,
                )
        }
        when (val retirement = activeIndexBootstrap.retire(project)) {
            is InstalledIndexBootstrapRetirement.Retired -> INDEX_BOOTSTRAP_LOG.info(
                "event=retired authority=${retirement.authority.name.lowercase()}",
            )
            is InstalledIndexBootstrapRetirement.Rejected -> return rejected(
                when (retirement.failure) {
                    InstalledIndexBootstrapRetirementFailure.MODULE_IDENTITY_LOST ->
                        InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_RETIREMENT_IDENTITY_LOST
                    InstalledIndexBootstrapRetirementFailure.PLATFORM_MUTATION_FAILED ->
                        InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_RETIREMENT_FAILED
                },
            )
        }
        when (applyInstalledGradleProjectPolicy(project)) {
            is InstalledGradleProjectPolicyApplication.Applied -> Unit
            InstalledGradleProjectPolicyApplication.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_PROJECT_POLICY_INVALID,
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
        when (val verification = activeIndexBootstrap.verifyImportedModel(project)) {
            is InstalledIndexExclusionVerification.Verified -> INDEX_BOOTSTRAP_LOG.info(
                "event=verified generatedSourceRootCount=${verification.generatedSourceRootCount}",
            )
            is InstalledIndexExclusionVerification.Rejected -> return rejected(
                when (verification.failure) {
                    InstalledIndexExclusionVerificationFailure.IMPORTED_MODULES_UNAVAILABLE ->
                        InstalledIntellijWorkspaceFailure
                            .INDEX_BOOTSTRAP_IMPORTED_MODULES_UNAVAILABLE
                    InstalledIndexExclusionVerificationFailure.EXCLUSION_ROOT_UNAVAILABLE ->
                        InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_EXCLUSION_ROOT_UNAVAILABLE
                    InstalledIndexExclusionVerificationFailure.EXCLUSION_NOT_PRESERVED ->
                        InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_EXCLUSION_NOT_PRESERVED
                    InstalledIndexExclusionVerificationFailure.SOURCE_ROOT_NOT_ADMITTED ->
                        InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_SOURCE_ROOT_NOT_ADMITTED
                    InstalledIndexExclusionVerificationFailure.PLATFORM_OBSERVATION_FAILED ->
                        InstalledIntellijWorkspaceFailure
                            .INDEX_BOOTSTRAP_PLATFORM_OBSERVATION_FAILED
                },
            )
        }
        val moduleRematerializer = InstalledModuleRematerializer {
            materializeImportedModules(project, workspaceRoot)
        }
        observer.observe(InstalledIntellijWorkspaceBootstrapPhase.INDEXING)
        when (
            val admission = awaitInstalledIndexingQuiescence(
                project,
                moduleRematerializer,
            ).workspaceOpeningAdmission()
        ) {
            InstalledWorkspaceIndexingAdmission.Ready -> Unit
            is InstalledWorkspaceIndexingAdmission.Rejected -> return rejected(admission.failure)
        }
        observer.observe(InstalledIntellijWorkspaceBootstrapPhase.MODEL_CAPTURE)
        val capture = when (val captured = captureInstalledGradleModel(project, workspaceRoot, importEnvironment.identity)) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> captured.value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> return rejected(
                captured.failure.workspaceFailure(),
            )
        }
        return InstalledIntellijWorkspaceOpening.Opened(
            InstalledIntellijWorkspaceModel(
                capture,
                projectStore.root,
                project,
                moduleRematerializer,
            ),
        )
    }

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
     * [InstalledGradleImportWait] closes cancellation, timeout, interruption, invalid JVM
     * configuration, and unexpected future failure. The future remains inside the installed import
     * boundary.
     */
    private fun awaitImport(
        future: CompletableFuture<InstalledGradleImportOutcome>,
    ): InstalledGradleImportWait = try {
        when (future.get(GRADLE_IMPORT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            InstalledGradleImportOutcome.Completed -> InstalledGradleImportWait.COMPLETED
            InstalledGradleImportOutcome.Failed -> InstalledGradleImportWait.FAILED
            InstalledGradleImportOutcome.Cancelled -> InstalledGradleImportWait.CANCELLED
            InstalledGradleImportOutcome.InvalidJvmConfiguration ->
                InstalledGradleImportWait.INVALID_JVM_CONFIGURATION
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

/** Project-open policy that admits only runtime-generated project configuration. */
internal fun installedProjectOpenTask(
    preparation: InstalledProjectOpenPreparation,
    indexBootstrap: InstalledIndexBootstrapBinder,
): OpenProjectTask = OpenProjectTask.build().copy(
    isNewProject = false,
    useDefaultProjectAsTemplate = false,
    isRefreshVfsNeeded = true,
    runConfigurators = false,
    runConversionBeforeOpen = false,
    preloadServices = true,
    preventIprLookup = true,
    createModule = false,
    beforeOpen = { project ->
        indexBootstrap.bind(project) &&
            preparation.prepare(project) is InstalledProjectOpenPreparationState.Prepared
    },
)

/** Closed result of replacing every persisted project-level Gradle policy with runtime policy. */
internal sealed interface InstalledGradleProjectPolicyApplication {
    data class Applied(
        val settings: GradleSettings,
    ) : InstalledGradleProjectPolicyApplication

    data object Rejected : InstalledGradleProjectPolicyApplication
}

/**
 * Proof transition: `Project -> InstalledGradleProjectPolicyApplication`.
 *
 * Applied establishes online Gradle operation, project-file storage beneath the isolated project
 * store, and automatic reload for every external build change. The boundary applies and reads back
 * this policy both before and after the exact Gradle root import. Rejected closes platform
 * persistence failure.
 */
private fun applyInstalledGradleProjectPolicy(
    project: Project,
): InstalledGradleProjectPolicyApplication = try {
    val settings = GradleSettings.getInstance(project)
    settings.isOfflineWork = false
    settings.storeProjectFilesExternally = false
    val tracker = ExternalSystemProjectTrackerSettings.getInstance(project)
    tracker.autoReloadType =
        ExternalSystemProjectTrackerSettings.AutoReloadType.ALL
    if (
        settings.isOfflineWork ||
        settings.storeProjectFilesExternally ||
        tracker.autoReloadType != ExternalSystemProjectTrackerSettings.AutoReloadType.ALL
    ) {
        InstalledGradleProjectPolicyApplication.Rejected
    } else {
        InstalledGradleProjectPolicyApplication.Applied(settings)
    }
} catch (_: RuntimeException) {
    InstalledGradleProjectPolicyApplication.Rejected
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
    INVALID_JVM_CONFIGURATION,
}

private fun InstalledProjectOpenPreparationFailure.workspaceFailure():
    InstalledIntellijWorkspaceFailure = when (this) {
        InstalledProjectOpenPreparationFailure.PROJECT_JVM_REJECTED ->
            InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE
    }

private fun InstalledSemanticProjectStoreFailure.workspaceFailure():
    InstalledIntellijWorkspaceFailure = when (this) {
        InstalledSemanticProjectStoreFailure.OVERLAPS_WORKSPACE ->
            InstalledIntellijWorkspaceFailure.PROJECT_STORE_OVERLAPS_WORKSPACE
        InstalledSemanticProjectStoreFailure.CREATION_FAILED ->
            InstalledIntellijWorkspaceFailure.PROJECT_STORE_CREATION_FAILED
        InstalledSemanticProjectStoreFailure.IDENTITY_REJECTED ->
            InstalledIntellijWorkspaceFailure.PROJECT_STORE_IDENTITY_REJECTED
        InstalledSemanticProjectStoreFailure.EXCLUSION_DISCOVERY_FAILED ->
            InstalledIntellijWorkspaceFailure.PROJECT_STORE_EXCLUSION_DISCOVERY_FAILED
        InstalledSemanticProjectStoreFailure.CONFIGURATION_WRITE_FAILED ->
            InstalledIntellijWorkspaceFailure.PROJECT_STORE_CONFIGURATION_WRITE_FAILED
    }

private fun InstalledIndexBootstrapActivationFailure.workspaceFailure():
    InstalledIntellijWorkspaceFailure = when (this) {
        InstalledIndexBootstrapActivationFailure.MODULE_UNAVAILABLE ->
            InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_MODULE_UNAVAILABLE
        InstalledIndexBootstrapActivationFailure.EXCLUSION_POLICY_MISMATCH ->
            InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_EXCLUSION_POLICY_MISMATCH
        InstalledIndexBootstrapActivationFailure.CONTENT_ROOT_MISMATCH ->
            InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_CONTENT_ROOT_MISMATCH
        InstalledIndexBootstrapActivationFailure.EXCLUSION_ROOTS_MISMATCH ->
            InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_EXCLUSION_ROOTS_MISMATCH
        InstalledIndexBootstrapActivationFailure.PLATFORM_OBSERVATION_FAILED ->
            InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_PLATFORM_OBSERVATION_FAILED
    }

private val INDEX_BOOTSTRAP_LOG = Logger.getInstance("io.github.amichne.kast.indexBootstrap")

private fun rejected(
    failure: InstalledIntellijWorkspaceFailure,
): InstalledIntellijWorkspaceOpening.Rejected = InstalledIntellijWorkspaceOpening.Rejected(failure)
