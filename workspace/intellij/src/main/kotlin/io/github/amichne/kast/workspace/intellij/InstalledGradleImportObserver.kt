package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class InstalledGradleImportObserver(
    private val workspaceRoot: Path,
) : ExternalSystemTaskNotificationListener {
    internal val completion = CompletableFuture<InstalledGradleImportOutcome>()
    private val cohortLock = Any()
    private var cohort: GradleImportCohort = GradleImportCohort.Collecting()

    override fun onStart(projectPath: String, id: ExternalSystemTaskId) {
        if (id.workspaceResolution(projectPath) == GradleTaskIdentity.EXACT_WORKSPACE) {
            synchronized(cohortLock) {
                cohort = when (val current = cohort) {
                    is GradleImportCohort.Collecting -> current.copy(
                        admitted = current.admitted + id,
                    )
                    is GradleImportCohort.Published -> current
                }
            }
        }
    }

    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
        if (id.workspaceResolution(projectPath) == GradleTaskIdentity.EXACT_WORKSPACE) {
            observeTerminal(
                id,
                GradleImportTerminal.COMPLETED,
                GradleTerminalAuthority.EXACT_PATH,
            )
        }
    }

    override fun onFailure(projectPath: String, id: ExternalSystemTaskId, exception: Exception) {
        if (id.workspaceResolution(projectPath) == GradleTaskIdentity.EXACT_WORKSPACE) {
            observeTerminal(
                id,
                GradleImportTerminal.FAILED,
                GradleTerminalAuthority.EXACT_PATH,
            )
        }
    }

    override fun onCancel(projectPath: String, id: ExternalSystemTaskId) {
        if (id.workspaceResolution(projectPath) == GradleTaskIdentity.EXACT_WORKSPACE) {
            observeTerminal(
                id,
                GradleImportTerminal.CANCELLED,
                GradleTerminalAuthority.EXACT_PATH,
            )
        }
    }

    override fun onSuccess(id: ExternalSystemTaskId) {
        observeTerminal(id, GradleImportTerminal.COMPLETED, GradleTerminalAuthority.ADMITTED_ID)
    }

    override fun onFailure(id: ExternalSystemTaskId, exception: Exception) {
        observeTerminal(id, GradleImportTerminal.FAILED, GradleTerminalAuthority.ADMITTED_ID)
    }

    override fun onCancel(id: ExternalSystemTaskId) {
        observeTerminal(id, GradleImportTerminal.CANCELLED, GradleTerminalAuthority.ADMITTED_ID)
    }

    private fun observeTerminal(
        id: ExternalSystemTaskId,
        terminal: GradleImportTerminal,
        authority: GradleTerminalAuthority,
    ) {
        val transition = synchronized(cohortLock) {
            cohort.transition(id, terminal, authority).also { observed ->
                cohort = observed.cohort
            }
        }
        when (transition) {
            is GradleImportCohortTransition.Retained -> Unit
            is GradleImportCohortTransition.Published -> completion.complete(transition.outcome)
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

internal sealed interface InstalledGradleImportOutcome {
    data object Completed : InstalledGradleImportOutcome
    data object Failed : InstalledGradleImportOutcome
    data object Cancelled : InstalledGradleImportOutcome
}

/**
 * Proof transition: `CompletableFuture<Void> -> CompletableFuture<InstalledGradleImportOutcome>`.
 *
 * Refines IntelliJ's callback future into closed completed, failed, or cancelled import data. The
 * platform `Void` future and exceptional completion remain confined to the External System
 * callback boundary.
 */
internal fun CompletableFuture<Void>.closedImportOutcome():
    CompletableFuture<InstalledGradleImportOutcome> = handle { _, failure ->
        when {
            isCancelled -> InstalledGradleImportOutcome.Cancelled
            failure == null -> InstalledGradleImportOutcome.Completed
            failure is java.util.concurrent.CancellationException ->
                InstalledGradleImportOutcome.Cancelled
            failure is CompletionException &&
                failure.cause is java.util.concurrent.CancellationException ->
                InstalledGradleImportOutcome.Cancelled
            else -> InstalledGradleImportOutcome.Failed
        }
    }

private enum class GradleTaskIdentity { EXACT_WORKSPACE, OTHER }
private enum class GradleTaskKind { PROJECT_RESOLUTION, OTHER }

private sealed interface GradleImportCohort {
    data class Collecting(
        val admitted: Set<ExternalSystemTaskId> = emptySet(),
        val remembered: GradleImportCohortMemory = GradleImportCohortMemory.NO_BLOCKER,
    ) : GradleImportCohort

    data class Published(val outcome: InstalledGradleImportOutcome) : GradleImportCohort
}

private sealed interface GradleImportCohortTransition {
    val cohort: GradleImportCohort

    data class Retained(
        override val cohort: GradleImportCohort,
    ) : GradleImportCohortTransition

    data class Published(
        override val cohort: GradleImportCohort.Published,
    ) : GradleImportCohortTransition {
        val outcome: InstalledGradleImportOutcome = cohort.outcome
    }
}

private enum class GradleImportTerminal {
    COMPLETED,
    CANCELLED,
    FAILED,
    ;

    fun outcome(): InstalledGradleImportOutcome = when (this) {
        COMPLETED -> InstalledGradleImportOutcome.Completed
        CANCELLED -> InstalledGradleImportOutcome.Cancelled
        FAILED -> InstalledGradleImportOutcome.Failed
    }
}

private enum class GradleImportCohortMemory {
    NO_BLOCKER,
    CANCELLED,
    FAILED,
    ;

    fun remember(terminal: GradleImportTerminal): GradleImportCohortMemory = when {
        this == FAILED || terminal == GradleImportTerminal.FAILED -> FAILED
        this == CANCELLED || terminal == GradleImportTerminal.CANCELLED -> CANCELLED
        else -> NO_BLOCKER
    }

    fun outcome(): InstalledGradleImportOutcome = when (this) {
        NO_BLOCKER -> InstalledGradleImportOutcome.Completed
        CANCELLED -> InstalledGradleImportOutcome.Cancelled
        FAILED -> InstalledGradleImportOutcome.Failed
    }
}

private enum class GradleTerminalAuthority { EXACT_PATH, ADMITTED_ID }

/**
 * Proof transition: `GradleImportCohort + ExternalSystemTaskId + GradleImportTerminal +
 * GradleTerminalAuthority -> GradleImportCohortTransition`.
 *
 * Retained establishes that every exact admitted task remains in one cohort until its own
 * terminal callback. Published establishes that the admitted set is empty and preserves any
 * observed failure or cancellation. An exact path-aware terminal without a start may publish only
 * from an empty cohort; an unadmitted ID-only terminal remains no evidence. Raw IntelliJ callback
 * identity is permitted only at [InstalledGradleImportObserver].
 */
private fun GradleImportCohort.transition(
    id: ExternalSystemTaskId,
    terminal: GradleImportTerminal,
    authority: GradleTerminalAuthority,
): GradleImportCohortTransition = when (this) {
    is GradleImportCohort.Published -> GradleImportCohortTransition.Retained(this)
    is GradleImportCohort.Collecting -> when {
        id in admitted -> {
            val remaining = admitted - id
            val terminalEvidence = remembered.remember(terminal)
            if (remaining.isEmpty()) {
                terminalEvidence.publish()
            } else {
                GradleImportCohortTransition.Retained(copy(
                    admitted = remaining,
                    remembered = terminalEvidence,
                ))
            }
        }
        authority == GradleTerminalAuthority.EXACT_PATH && admitted.isEmpty() -> terminal.publish()
        else -> GradleImportCohortTransition.Retained(this)
    }
}

private fun GradleImportTerminal.publish(): GradleImportCohortTransition.Published {
    val outcome = outcome()
    return GradleImportCohortTransition.Published(
        GradleImportCohort.Published(outcome),
    )
}

private fun GradleImportCohortMemory.publish(): GradleImportCohortTransition.Published {
    val outcome = outcome()
    return GradleImportCohortTransition.Published(GradleImportCohort.Published(outcome))
}
