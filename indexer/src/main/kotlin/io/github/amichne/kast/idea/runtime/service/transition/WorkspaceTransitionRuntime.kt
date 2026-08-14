package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.idea.transition.CoordinatedVfsRefreshAuthority
import io.github.amichne.kast.idea.transition.WorkspaceVfsObservationScope
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectImportBridge
import io.github.amichne.kast.workspace.intellij.IntellijWorkspaceEffects
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

internal val RECOVERY_AUDIT_DELAY: Duration = Duration.ofMinutes(5)

internal class WorkspaceVfsRefreshScope private constructor(
    val roots: Set<Path>,
) {
    companion object {
        /**
         * Proof transition: `WorkspaceVfsObservationScope -> WorkspaceVfsRefreshScope`.
         *
         * Establishes that every filesystem authority capable of changing the
         * workspace's semantic identity is covered by one minimal recursive
         * refresh root. Nested authorities are removed without discarding their
         * coverage. Raw compiler and classpath root providers are extracted only
         * at this transition boundary; [WorkspaceVfsRefreshScope.roots] may be
         * extracted only by the workspace IntelliJ effect adapter.
         */
        fun from(scope: WorkspaceVfsObservationScope): WorkspaceVfsRefreshScope {
            val candidates = buildSet {
                add(scope.workspaceRoot)
                add(scope.buildSemanticRoot)
                scope.configurationFiles.mapNotNullTo(this) { path -> path.parent }
                addAll(scope.compilerSourceRoots())
                addAll(scope.classpathRoots())
            }.mapTo(linkedSetOf()) { path -> path.toAbsolutePath().normalize() }
            val minimalRoots = candidates
                .sortedWith(compareBy<Path>({ it.nameCount }, Path::toString))
                .filterTo(linkedSetOf()) { candidate ->
                    candidates.none { other ->
                        other != candidate &&
                        other.nameCount < candidate.nameCount &&
                        candidate.startsWith(other)
                    }
                }
            return WorkspaceVfsRefreshScope(minimalRoots)
        }
    }
}

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
 * signals. Explicit source refresh and recovery apply IntelliJ-tracked changes
 * recursively across every scoped filesystem authority that can affect
 * workspace semantics, rather than scanning every VFS root registered in the
 * process or forcing clean roots dirty. Signals whose only production ingress
 * is the VFS observer reuse that proof, and build-semantic observation requests
 * Gradle model refresh. Raw signal membership is interpreted only at this
 * transition boundary.
 */
internal fun workspaceRefreshPlan(
    signals: Set<WorkspaceSignal>,
): WorkspaceRefreshPlan = when {
    WorkspaceSignal.RecoveryAudit in signals -> WorkspaceRefreshPlan.GlobalVfsThenGradle
    WorkspaceSignal.InitialProjectModel in signals && WorkspaceSignal.BuildSemantic in signals ->
        WorkspaceRefreshPlan.GlobalVfsThenGradle
    WorkspaceSignal.Source in signals && WorkspaceSignal.BuildSemantic in signals ->
        WorkspaceRefreshPlan.GlobalVfsThenGradle
    WorkspaceSignal.RecoveryProbe in signals && WorkspaceSignal.BuildSemantic in signals ->
        WorkspaceRefreshPlan.GlobalVfsThenGradle
    WorkspaceSignal.Source in signals ||
    WorkspaceSignal.RecoveryProbe in signals ||
    WorkspaceSignal.InitialProjectModel in signals -> WorkspaceRefreshPlan.GlobalVfs
    WorkspaceSignal.BuildSemantic in signals -> WorkspaceRefreshPlan.ObservedVfsThenGradle
    else -> WorkspaceRefreshPlan.ObservedVfs
}

internal fun refreshWorkspaceModels(
    project: Project,
    gradleBuildRoot: Path,
    signals: Set<WorkspaceSignal>,
    vfsRefreshAuthority: CoordinatedVfsRefreshAuthority,
    vfsObservationScope: WorkspaceVfsObservationScope,
) {
    when (workspaceRefreshPlan(signals)) {
        WorkspaceRefreshPlan.ObservedVfs -> Unit
        WorkspaceRefreshPlan.ObservedVfsThenGradle -> refreshGradleModel(project, gradleBuildRoot)
        WorkspaceRefreshPlan.GlobalVfs -> refreshWorkspaceVfs(
            vfsRefreshAuthority,
            WorkspaceVfsRefreshScope.from(vfsObservationScope),
        )
        WorkspaceRefreshPlan.GlobalVfsThenGradle -> {
            refreshWorkspaceVfs(
                vfsRefreshAuthority,
                WorkspaceVfsRefreshScope.from(vfsObservationScope),
            )
            refreshGradleModel(project, gradleBuildRoot)
        }
    }
    GradleProjectImportBridge.awaitGradleModelSettlement(project)
}

internal fun refreshWorkspaceVfs(
    authority: CoordinatedVfsRefreshAuthority,
    scope: WorkspaceVfsRefreshScope,
) {
    authority.runGlobalRefresh {
        IntellijWorkspaceEffects.refreshNioFiles(scope.roots)
    }
}

private fun refreshGradleModel(
    project: Project,
    gradleBuildRoot: Path,
) {
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
