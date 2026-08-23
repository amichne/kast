package io.github.amichne.kast.workspace.intellij

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.startup.StartupManager
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.settings.GradleSystemSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
import java.util.concurrent.CancellationException
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
    GRADLE_LINK_RESET_FAILED,
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

/** Detached complete model proof from one exact IntelliJ-opened Gradle workspace. */
class InstalledIntellijWorkspaceModel internal constructor(
    val capture: InstalledGradleModelCapture,
    private val project: Project,
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
        > = when (awaitInstalledIndexingQuiescence(project)) {
        InstalledIndexingReadiness.READY -> capture.captureCurrentSemanticIdentity()
        InstalledIndexingReadiness.INTERRUPTED,
        InstalledIndexingReadiness.TIMED_OUT,
        InstalledIndexingReadiness.FAILED,
            -> io.github.amichne.kast.kernel.Refinement.Rejected(
                InstalledGradleModelCaptureFailure.INDEXING_UNAVAILABLE,
            )
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
     * completed one fresh Gradle link, reached smart mode, and detached one complete Gradle
     * model. [InstalledIntellijWorkspaceFailure] closes every expected bootstrap failure. The live
     * project and Gradle objects remain inside this adapter and the IntelliJ project lifecycle.
     */
    fun open(workspaceRoot: Path): InstalledIntellijWorkspaceOpening {
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
        val project = try {
            ProjectManagerEx.getInstanceEx().openProject(workspaceRoot, openProjectTask())
        } catch (_: RuntimeException) {
            null
        } ?: return rejected(InstalledIntellijWorkspaceFailure.PROJECT_OPEN_FAILED)

        val projectJvm = InstalledProjectJvm.from(gradleJvm)
        when (projectJvm.assign(project)) {
            InstalledProjectJvmAssignment.Assigned -> Unit
            is InstalledProjectJvmAssignment.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE,
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

        val imported = CompletableFuture<Void>()
        val specification = ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
            .withCallback(imported)
        val gradleSettings = GradleSettings.getInstance(project)
        when (linkedProjectSettings(gradleSettings, workspaceRoot)) {
            GradleLinkState.Linked -> if (
                !gradleSettings.unlinkExternalProject(
                    workspaceRoot.toString(),
                )
            ) {
                return rejected(InstalledIntellijWorkspaceFailure.GRADLE_LINK_RESET_FAILED)
            }
            GradleLinkState.Unlinked -> Unit
        }
        val importCompletion = try {
            val settings = GradleProjectSettings(workspaceRoot.toString()).apply {
                this.gradleJvm = gradleJvm.projectSettingsSelector()
            }
            ExternalSystemUtil.linkExternalProject(settings, specification)
            imported
        } catch (_: RuntimeException) {
            return rejected(InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED)
        }
        when (await(importCompletion)) {
            FutureCompletion.COMPLETED -> Unit
            FutureCompletion.INTERRUPTED -> return rejected(
                InstalledIntellijWorkspaceFailure.INDEXING_INTERRUPTED,
            )
            FutureCompletion.TIMED_OUT -> return rejected(
                InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_TIMED_OUT,
            )
            FutureCompletion.FAILED -> return rejected(
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
        when (projectJvm.assign(project)) {
            InstalledProjectJvmAssignment.Assigned -> Unit
            is InstalledProjectJvmAssignment.Rejected -> return rejected(
                InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE,
            )
        }
        when (awaitInstalledIndexingQuiescence(project)) {
            InstalledIndexingReadiness.READY -> Unit
            InstalledIndexingReadiness.INTERRUPTED -> return rejected(
                InstalledIntellijWorkspaceFailure.INDEXING_INTERRUPTED,
            )
            InstalledIndexingReadiness.TIMED_OUT,
            InstalledIndexingReadiness.FAILED,
                -> return rejected(InstalledIntellijWorkspaceFailure.MODEL_UNAVAILABLE)
        }
        val capture = when (val captured = captureInstalledGradleModel(project, workspaceRoot)) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> captured.value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> return rejected(
                captured.failure.workspaceFailure(),
            )
        }
        return InstalledIntellijWorkspaceOpening.Opened(
            InstalledIntellijWorkspaceModel(capture, project),
        )
    }

    private fun openProjectTask(): OpenProjectTask = OpenProjectTask.build().copy(
        isRefreshVfsNeeded = true,
        runConfigurators = true,
        runConversionBeforeOpen = false,
        preloadServices = true,
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
     * Proof transition: `Project + Path -> GradleLinkState`.
     *
     * Establishes whether the exact normalized root already has one linked Gradle settings
     * authority. [GradleLinkState.Unlinked] is the closed absent state. Raw platform settings
     * remain inside the Gradle import boundary.
     */
    private fun linkedProjectSettings(
        gradleSettings: GradleSettings,
        workspaceRoot: Path,
    ): GradleLinkState {
        gradleSettings.linkedProjectsSettings.forEach { settings ->
            if (
                settings.externalProjectPath?.let(Path::of)?.toAbsolutePath()?.normalize() ==
                workspaceRoot
            ) {
                return GradleLinkState.Linked
            }
        }
        return GradleLinkState.Unlinked
    }

    private fun await(future: CompletableFuture<Void>): FutureCompletion = try {
        future.get(GRADLE_IMPORT_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        FutureCompletion.COMPLETED
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        FutureCompletion.INTERRUPTED
    } catch (_: TimeoutException) {
        FutureCompletion.TIMED_OUT
    } catch (_: ExecutionException) {
        FutureCompletion.FAILED
    } catch (_: CancellationException) {
        FutureCompletion.FAILED
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
        val hasModules = try {
            ReadAction.nonBlocking<Boolean> {
                ModuleManager.getInstance(project).modules.any { module -> !module.isDisposed }
            }.executeSynchronously()
        } catch (_: RuntimeException) {
            return InstalledModuleMaterialization.FAILED
        }
        if (hasModules) return InstalledModuleMaterialization.AVAILABLE

        val dataManager = ProjectDataManager.getInstance()
        val structure = dataManager.getExternalProjectsData(project, GradleConstants.SYSTEM_ID)
            .singleOrNull { info ->
                Path.of(info.externalProjectPath).toAbsolutePath().normalize() == workspaceRoot
            }
            ?.externalProjectStructure
            ?: return InstalledModuleMaterialization.UNAVAILABLE
        return try {
            dataManager.ensureTheDataIsReadyToUse(structure)
            dataManager.importData(structure, project)
            InstalledModuleMaterialization.IMPORTED
        } catch (_: RuntimeException) {
            InstalledModuleMaterialization.FAILED
        }
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

private sealed interface GradleLinkState {
    data object Linked : GradleLinkState
    data object Unlinked : GradleLinkState
}

private enum class FutureCompletion {
    COMPLETED,
    INTERRUPTED,
    TIMED_OUT,
    FAILED,
}

private enum class InstalledModuleMaterialization { AVAILABLE, IMPORTED, UNAVAILABLE, FAILED }

private fun rejected(
    failure: InstalledIntellijWorkspaceFailure,
): InstalledIntellijWorkspaceOpening.Rejected = InstalledIntellijWorkspaceOpening.Rejected(failure)
