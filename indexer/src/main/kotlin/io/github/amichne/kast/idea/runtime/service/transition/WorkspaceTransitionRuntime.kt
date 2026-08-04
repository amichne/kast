package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectImportBridge
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal const val RECOVERY_AUDIT_MILLIS = 300_000L
private const val GRADLE_REFRESH_TIMEOUT_MINUTES = 5L

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
        refresh.get(GRADLE_REFRESH_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    }
    GradleProjectImportBridge.awaitGradleModelSettlement(project)
}

internal fun indexingRetryDelayMillis(consecutiveFailures: Int): Long = when (consecutiveFailures) {
    1 -> 250L
    2 -> 500L
    3 -> 1_000L
    else -> RECOVERY_AUDIT_MILLIS
}

internal fun loadLiveIndexingConfig(
    workspaceRoot: Path,
    lastValid: KastConfig,
): KastConfig = lastValid.copy(indexing = KastConfig.load(workspaceRoot).indexing)
