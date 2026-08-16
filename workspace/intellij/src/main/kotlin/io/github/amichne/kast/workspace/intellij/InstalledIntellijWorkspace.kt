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
    GRADLE_IMPORT_FAILED,
    GRADLE_IMPORT_TIMED_OUT,
    INDEXING_INTERRUPTED,
    MODEL_UNAVAILABLE,
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
            if (isLinked(project, workspaceRoot)) {
                ExternalSystemUtil.refreshProject(workspaceRoot.toString(), specification)
            } else {
                val settings = GradleProjectSettings(workspaceRoot.toString())
                ExternalSystemUtil.linkExternalProject(settings, specification)
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
        val capture = captureInstalledGradleModel(project, workspaceRoot)
            ?: return rejected(InstalledIntellijWorkspaceFailure.MODEL_UNAVAILABLE)
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

    private fun isLinked(
        project: com.intellij.openapi.project.Project,
        workspaceRoot: Path,
    ): Boolean = GradleSettings.getInstance(project).linkedProjectsSettings.any { settings ->
        settings.externalProjectPath?.let(Path::of)?.toAbsolutePath()?.normalize() == workspaceRoot
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

private enum class FutureCompletion {
    COMPLETED,
    INTERRUPTED,
    TIMED_OUT,
    FAILED,
}

private fun rejected(
    failure: InstalledIntellijWorkspaceFailure,
): InstalledIntellijWorkspaceOpening.Rejected = InstalledIntellijWorkspaceOpening.Rejected(failure)
