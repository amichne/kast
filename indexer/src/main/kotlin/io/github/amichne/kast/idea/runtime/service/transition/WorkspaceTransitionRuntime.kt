package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.CoordinatedVfsRefreshAuthority
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectImportBridge
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

internal val RECOVERY_AUDIT_DELAY: Duration = Duration.ofMinutes(5)

internal sealed interface WorkspaceRefreshPlan {
    data object ObservedVfs : WorkspaceRefreshPlan

    data object ObservedVfsThenGradle : WorkspaceRefreshPlan

    data object GlobalVfs : WorkspaceRefreshPlan

    data object GlobalVfsThenGradle : WorkspaceRefreshPlan
}

/**
 * Proof transition: `Set<WorkspaceSignal> -> WorkspaceRefreshPlan`.
 *
 * Establishes the exact refresh authority carried by already-observed VFS
 * signals. Explicit source refresh and recovery retain global VFS discovery
 * authority. Signals whose only production ingress is the VFS observer reuse
 * that proof, and build-semantic observation requests Gradle model refresh.
 * Raw signal membership is interpreted only at this transition boundary.
 */
internal fun workspaceRefreshPlan(
    signals: Set<WorkspaceSignal>,
): WorkspaceRefreshPlan = when {
    WorkspaceSignal.RecoveryAudit in signals -> WorkspaceRefreshPlan.GlobalVfsThenGradle
    WorkspaceSignal.Source in signals && WorkspaceSignal.BuildSemantic in signals ->
        WorkspaceRefreshPlan.GlobalVfsThenGradle
    WorkspaceSignal.RecoveryProbe in signals && WorkspaceSignal.BuildSemantic in signals ->
        WorkspaceRefreshPlan.GlobalVfsThenGradle
    WorkspaceSignal.Source in signals || WorkspaceSignal.RecoveryProbe in signals -> WorkspaceRefreshPlan.GlobalVfs
    WorkspaceSignal.BuildSemantic in signals -> WorkspaceRefreshPlan.ObservedVfsThenGradle
    else -> WorkspaceRefreshPlan.ObservedVfs
}

internal fun refreshWorkspaceModels(
    project: Project,
    gradleBuildRoot: Path,
    signals: Set<WorkspaceSignal>,
    vfsRefreshAuthority: CoordinatedVfsRefreshAuthority,
) {
    when (workspaceRefreshPlan(signals)) {
        WorkspaceRefreshPlan.ObservedVfs -> Unit
        WorkspaceRefreshPlan.ObservedVfsThenGradle -> refreshGradleModel(project, gradleBuildRoot)
        WorkspaceRefreshPlan.GlobalVfs -> refreshGlobalVfs(vfsRefreshAuthority)
        WorkspaceRefreshPlan.GlobalVfsThenGradle -> {
            refreshGlobalVfs(vfsRefreshAuthority)
            refreshGradleModel(project, gradleBuildRoot)
        }
    }
    GradleProjectImportBridge.awaitGradleModelSettlement(project)
}

private fun refreshGlobalVfs(authority: CoordinatedVfsRefreshAuthority) {
    authority.runGlobalRefresh {
        ApplicationManager.getApplication().invokeAndWait {
            VirtualFileManager.getInstance().syncRefresh()
        }
    }
}

private fun refreshGradleModel(project: Project, gradleBuildRoot: Path) {
    val refresh = CompletableFuture<Void>()
    IdeaGradleProjectLoadBridge.refreshExternalGradleProject(project, gradleBuildRoot, refresh)
    GradleProjectImportBridge.awaitGradleRefresh(project, refresh)
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
