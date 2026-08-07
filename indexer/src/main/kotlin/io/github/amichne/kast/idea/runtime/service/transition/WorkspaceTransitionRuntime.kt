package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectImportBridge
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

internal val RECOVERY_AUDIT_DELAY: Duration = Duration.ofMinutes(5)

internal enum class WorkspaceModelRefreshRequirement {
    VfsOnly,
    Gradle,
}

internal fun workspaceModelRefreshRequirement(
    signals: Set<WorkspaceSignal>,
): WorkspaceModelRefreshRequirement = if (
    WorkspaceSignal.BuildSemantic in signals || WorkspaceSignal.RecoveryAudit in signals
) {
    WorkspaceModelRefreshRequirement.Gradle
} else {
    WorkspaceModelRefreshRequirement.VfsOnly
}

internal fun refreshWorkspaceModels(
    project: Project,
    gradleBuildRoot: Path,
    signals: Set<WorkspaceSignal>,
) {
    ApplicationManager.getApplication().invokeAndWait {
        VirtualFileManager.getInstance().syncRefresh()
    }
    if (workspaceModelRefreshRequirement(signals) == WorkspaceModelRefreshRequirement.Gradle) {
        val refresh = CompletableFuture<Void>()
        IdeaGradleProjectLoadBridge.refreshExternalGradleProject(project, gradleBuildRoot, refresh)
        GradleProjectImportBridge.awaitGradleRefresh(project, refresh)
    }
    GradleProjectImportBridge.awaitGradleModelSettlement(project)
}

@JvmInline
internal value class ConsecutiveIndexingFailures private constructor(
    private val count: Int,
) {
    fun afterFailure(): ConsecutiveIndexingFailures = if (count == Int.MAX_VALUE) {
        this
    } else {
        ConsecutiveIndexingFailures(count + 1)
    }

    val retryDelay: Duration
        get() = when (count) {
            0, 1 -> Duration.ofMillis(250)
            2 -> Duration.ofMillis(500)
            3 -> Duration.ofSeconds(1)
            4 -> Duration.ofSeconds(2)
            else -> Duration.ofSeconds(5)
        }

    companion object {
        /**
         * Proof transition: `Unit -> ConsecutiveIndexingFailures`.
         *
         * Establishes the closed zero-failure retry state. Subsequent failures
         * advance only through [afterFailure], and callers consume the typed
         * [retryDelay] rather than interpreting a primitive counter.
         */
        fun none(): ConsecutiveIndexingFailures = ConsecutiveIndexingFailures(0)
    }
}

internal fun loadLiveIndexingConfig(
    workspaceRoot: Path,
    lastValid: KastConfig,
): KastConfig = lastValid.copy(indexing = KastConfig.load(workspaceRoot).indexing)
