package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.fields.ProjectOpenGradleLoadEnabled
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

internal object KastProjectOpenGradleLoad {
    fun execute(
        project: Project,
        workspaceRoot: Path,
        enabled: ProjectOpenGradleLoadEnabled,
        onComplete: (Throwable?) -> Unit = {},
        scheduleGradleLoad: ((() -> Unit) -> Unit) = { task ->
            ApplicationManager.getApplication().executeOnPooledThread(task)
        },
    ): ProjectOpenGradleLoadResult {
        if (!enabled.value) {
            return ProjectOpenGradleLoadResult.Skipped("disabled")
        }

        val externalProjectPath = workspaceRoot.toAbsolutePath().normalize()
        if (!isGradleWorkspace(externalProjectPath)) {
            return ProjectOpenGradleLoadResult.Skipped("not a Gradle project")
        }

        val request = if (isExternalGradleProjectLinked(project, externalProjectPath)) {
            GradleProjectLoadRequest.Refresh(externalProjectPath)
        } else {
            GradleProjectLoadRequest.Link(externalProjectPath)
        }

        return runCatching {
            scheduleGradleLoad {
                requestGradleProjectLoad(project, request, onComplete)
            }
            ProjectOpenGradleLoadResult.Requested(request)
        }.getOrElse { error ->
            ProjectOpenGradleLoadResult.Failed(error.message ?: error::class.java.name)
        }
    }

    fun log(result: ProjectOpenGradleLoadResult) {
        when (result) {
            is ProjectOpenGradleLoadResult.Requested ->
                LOG.info("Kast requested Gradle project ${result.request.verb} for ${result.request.externalProjectPath}")
            is ProjectOpenGradleLoadResult.Skipped ->
                LOG.info("Kast Gradle project load skipped: ${result.reason}")
            is ProjectOpenGradleLoadResult.Failed ->
                LOG.warn("Kast Gradle project load request failed: ${result.message}")
        }
    }

    private fun isGradleWorkspace(workspaceRoot: Path): Boolean =
        GRADLE_MARKERS.any { marker -> Files.isRegularFile(workspaceRoot.resolve(marker)) }

    private fun requestGradleProjectLoad(
        project: Project,
        request: GradleProjectLoadRequest,
        onComplete: (Throwable?) -> Unit,
    ) {
        val externalProjectPathString = request.externalProjectPath.toString()
        runCatching {
            val importFuture = CompletableFuture<Void>()
            importFuture.whenComplete { _, error ->
                if (error == null) {
                    LOG.info("Kast Gradle project ${request.verb} completed for $externalProjectPathString")
                } else {
                    LOG.warn("Kast Gradle project ${request.verb} failed for $externalProjectPathString", error)
                }
                onComplete(error)
            }
            when (request) {
                is GradleProjectLoadRequest.Link ->
                    IdeaGradleProjectLoadBridge.linkExternalGradleProject(
                        project,
                        request.externalProjectPath,
                        importFuture,
                    )
                is GradleProjectLoadRequest.Refresh ->
                    IdeaGradleProjectLoadBridge.refreshExternalGradleProject(
                        project,
                        request.externalProjectPath,
                        importFuture,
                    )
            }
        }.onFailure { error ->
            LOG.warn("Kast Gradle project ${request.verb} request failed for $externalProjectPathString", error)
            onComplete(error)
        }
    }

    private fun isExternalGradleProjectLinked(project: Project, externalProjectPath: Path): Boolean =
        runCatching {
            IdeaGradleProjectLoadBridge.isExternalGradleProjectLinked(project, externalProjectPath)
        }.getOrElse { error ->
            LOG.warn("Kast could not inspect linked Gradle projects for $externalProjectPath", error)
            false
        }

    private val GRADLE_MARKERS = listOf(
        "settings.gradle.kts",
        "settings.gradle",
        "build.gradle.kts",
        "build.gradle",
    )

    private val LOG = Logger.getInstance(KastProjectOpenGradleLoad::class.java)
}

internal sealed class GradleProjectLoadRequest {
    abstract val externalProjectPath: Path
    abstract val verb: String

    data class Link(override val externalProjectPath: Path) : GradleProjectLoadRequest() {
        override val verb: String = "link"
    }

    data class Refresh(override val externalProjectPath: Path) : GradleProjectLoadRequest() {
        override val verb: String = "refresh"
    }
}

internal sealed class ProjectOpenGradleLoadResult {
    data class Requested(val request: GradleProjectLoadRequest) : ProjectOpenGradleLoadResult()
    data class Skipped(val reason: String) : ProjectOpenGradleLoadResult()
    data class Failed(val message: String) : ProjectOpenGradleLoadResult()
}
