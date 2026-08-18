package io.github.amichne.kast.workspace.intellij

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.startup.StartupManager
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
    PROJECT_OPEN_FAILED,
    STARTUP_FAILED,
    GRADLE_JVM_UNAVAILABLE,
    GRADLE_IMPORT_FAILED,
    GRADLE_IMPORT_TIMED_OUT,
    INDEXING_INTERRUPTED,
    MODEL_UNAVAILABLE,
    MODEL_ROOT_UNAVAILABLE,
    MODEL_EXTERNAL_PROJECT_UNAVAILABLE,
    MODEL_EXTERNAL_PROJECT_INCOMPLETE,
    MODEL_SOURCE_ROOTS_UNAVAILABLE,
    MODEL_SOURCE_STATE_UNAVAILABLE,
    MODEL_IDENTITIES_UNAVAILABLE,
}

/** Detached complete model proof from one exact IntelliJ-opened Gradle workspace. */
class InstalledIntellijWorkspaceModel internal constructor(
    val capture: InstalledGradleModelCapture,
)

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
     * completed one Gradle link or refresh, reached smart mode, and detached one complete Gradle
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
        try {
            when (val link = linkedProjectSettings(project, workspaceRoot)) {
                is GradleLinkState.Linked -> {
                    link.settings.gradleJvm = gradleJvm.projectSettingsSelector()
                    ExternalSystemUtil.refreshProject(workspaceRoot.toString(), specification)
                }
                GradleLinkState.Unlinked -> {
                    val settings = GradleProjectSettings(workspaceRoot.toString()).apply {
                        this.gradleJvm = gradleJvm.projectSettingsSelector()
                    }
                    ExternalSystemUtil.linkExternalProject(settings, specification)
                }
            }
        } catch (_: RuntimeException) {
            return rejected(InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED)
        }
        when (await(imported)) {
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
        try {
            DumbService.getInstance(project).waitForSmartMode()
        } catch (_: RuntimeException) {
            return rejected(InstalledIntellijWorkspaceFailure.MODEL_UNAVAILABLE)
        }
        val capture = when (val captured = captureInstalledGradleModel(project, workspaceRoot)) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> captured.value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> return rejected(
                captured.failure.workspaceFailure(),
            )
        }
        return InstalledIntellijWorkspaceOpening.Opened(
            InstalledIntellijWorkspaceModel(capture),
        )
    }

    private fun openProjectTask(): OpenProjectTask = OpenProjectTask.build().copy(
        isRefreshVfsNeeded = false,
        runConfigurators = false,
        runConversionBeforeOpen = false,
        preloadServices = false,
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
        project: com.intellij.openapi.project.Project,
        workspaceRoot: Path,
    ): GradleLinkState {
        GradleSettings.getInstance(project).linkedProjectsSettings.forEach { settings ->
            if (
                settings.externalProjectPath?.let(Path::of)?.toAbsolutePath()?.normalize() ==
                workspaceRoot
            ) {
                return GradleLinkState.Linked(settings)
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
        InstalledGradleModelCaptureFailure.IDENTITIES_UNAVAILABLE ->
            InstalledIntellijWorkspaceFailure.MODEL_IDENTITIES_UNAVAILABLE
    }

private sealed interface GradleLinkState {
    data class Linked(
        val settings: GradleProjectSettings,
    ) : GradleLinkState

    data object Unlinked : GradleLinkState
}

private enum class FutureCompletion {
    COMPLETED,
    INTERRUPTED,
    TIMED_OUT,
    FAILED,
}

private fun rejected(
    failure: InstalledIntellijWorkspaceFailure,
): InstalledIntellijWorkspaceOpening.Rejected = InstalledIntellijWorkspaceOpening.Rejected(failure)
