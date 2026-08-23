package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class InstalledGradleImportObserver(
    private val workspaceRoot: Path,
) : ExternalSystemTaskNotificationListener {
    internal val completion = CompletableFuture<Void>()

    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
        if (id.workspaceResolution(projectPath) == GradleTaskIdentity.EXACT_WORKSPACE) {
            completion.complete(null)
        }
    }

    override fun onFailure(projectPath: String, id: ExternalSystemTaskId, exception: Exception) {
        if (id.workspaceResolution(projectPath) == GradleTaskIdentity.EXACT_WORKSPACE) {
            completion.completeExceptionally(exception)
        }
    }

    override fun onCancel(projectPath: String, id: ExternalSystemTaskId) {
        if (id.workspaceResolution(projectPath) == GradleTaskIdentity.EXACT_WORKSPACE) {
            completion.cancel(false)
        }
    }

    override fun onSuccess(id: ExternalSystemTaskId) {
        if (id.workspaceResolution() == GradleTaskIdentity.EXACT_WORKSPACE) {
            completion.complete(null)
        }
    }

    override fun onFailure(id: ExternalSystemTaskId, exception: Exception) {
        if (id.workspaceResolution() == GradleTaskIdentity.EXACT_WORKSPACE) {
            completion.completeExceptionally(exception)
        }
    }

    override fun onCancel(id: ExternalSystemTaskId) {
        if (id.workspaceResolution() == GradleTaskIdentity.EXACT_WORKSPACE) {
            completion.cancel(false)
        }
    }

    /**
     * Proof transition: `String + ExternalSystemTaskId -> GradleTaskIdentity`.
     *
     * [GradleTaskIdentity.EXACT_WORKSPACE] establishes one Gradle project-resolution task whose
     * contextual path is the exact canonical workspace. Invalid or inaccessible raw paths fail
     * closed as [GradleTaskIdentity.OTHER]. Raw callback data remains inside this observer.
     */
    private fun ExternalSystemTaskId.workspaceResolution(projectPath: String): GradleTaskIdentity {
        return when (projectResolutionKind()) {
            GradleTaskKind.OTHER -> GradleTaskIdentity.OTHER
            GradleTaskKind.PROJECT_RESOLUTION -> try {
                if (Path.of(projectPath).toRealPath() == workspaceRoot) {
                    GradleTaskIdentity.EXACT_WORKSPACE
                } else {
                    GradleTaskIdentity.OTHER
                }
            } catch (_: IOException) {
                GradleTaskIdentity.OTHER
            } catch (_: RuntimeException) {
                GradleTaskIdentity.OTHER
            }
        }
    }

    /**
     * Proof transition: `ExternalSystemTaskId -> GradleTaskIdentity`.
     *
     * [GradleTaskIdentity.EXACT_WORKSPACE] establishes one Gradle project-resolution task for the
     * exact canonical workspace. Missing or invalid project identity fails closed as
     * [GradleTaskIdentity.OTHER]. Raw platform task identity remains inside this observer.
     */
    private fun ExternalSystemTaskId.workspaceResolution(): GradleTaskIdentity {
        return when (projectResolutionKind()) {
            GradleTaskKind.OTHER -> GradleTaskIdentity.OTHER
            GradleTaskKind.PROJECT_RESOLUTION -> {
                val basePath = findProject()?.basePath ?: return GradleTaskIdentity.OTHER
                try {
                    if (Path.of(basePath).toAbsolutePath().normalize() == workspaceRoot) {
                        GradleTaskIdentity.EXACT_WORKSPACE
                    } else {
                        GradleTaskIdentity.OTHER
                    }
                } catch (_: RuntimeException) {
                    GradleTaskIdentity.OTHER
                }
            }
        }
    }

    /**
     * Proof transition: `ExternalSystemTaskId -> GradleTaskKind`.
     *
     * [GradleTaskKind.PROJECT_RESOLUTION] establishes a Gradle `RESOLVE_PROJECT` task. Every other
     * external-system task fails closed as [GradleTaskKind.OTHER]. Raw task fields remain inside
     * this observer.
     */
    private fun ExternalSystemTaskId.projectResolutionKind(): GradleTaskKind =
        if (
            projectSystemId == GradleConstants.SYSTEM_ID &&
            type == ExternalSystemTaskType.RESOLVE_PROJECT
        ) {
            GradleTaskKind.PROJECT_RESOLUTION
        } else {
            GradleTaskKind.OTHER
        }
}

private enum class GradleTaskIdentity { EXACT_WORKSPACE, OTHER }
private enum class GradleTaskKind { PROJECT_RESOLUTION, OTHER }
