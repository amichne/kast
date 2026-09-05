package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkException
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
                    is GradleImportCohort.Collecting -> current.admit(id)
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

    /**
     * Closes project-open task admission after IntelliJ startup scheduling has passed.
     *
     * A terminal observation may publish immediately only after this transition and only when no
     * exact task remains active. Before closure, an empty cancelled generation remains provisional
     * so a later exact replacement can refine it.
     */
    internal fun closeProjectOpenAdmission() {
        val transition = synchronized(cohortLock) {
            cohort.closeAdmission().also { observed ->
                cohort = observed.cohort
            }
        }
        publish(transition)
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
        publish(transition)
    }

    private fun publish(transition: GradleImportCohortTransition) {
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
    data object InvalidJvmConfiguration : InstalledGradleImportOutcome
    data object InitializationScriptUnavailable : InstalledGradleImportOutcome
    data class IncompatiblePayload(val major: GradlePayloadClassFileMajor) : InstalledGradleImportOutcome
}

/**
 * Proof transition: `CompletableFuture<Void> -> CompletableFuture<InstalledGradleImportOutcome>`.
 *
 * Refines IntelliJ's callback future into closed completed, failed, cancelled, or invalid-JVM
 * import data. The platform `Void` future and exceptional completion remain confined to the
 * External System callback boundary.
 */
internal fun CompletableFuture<Void>.closedImportOutcome(
    observer: InstalledGradleImportDiagnosticObserver = InstalledGradleImportDiagnosticObserver {},
): CompletableFuture<InstalledGradleImportOutcome> = handle { _, failure ->
        when {
            isCancelled -> InstalledGradleImportOutcome.Cancelled
            failure == null -> InstalledGradleImportOutcome.Completed
            failure is java.util.concurrent.CancellationException ->
                InstalledGradleImportOutcome.Cancelled
            failure is CompletionException &&
                failure.cause is java.util.concurrent.CancellationException ->
                InstalledGradleImportOutcome.Cancelled
            failure.hasCause<ExternalSystemJdkException>() ->
                InstalledGradleImportOutcome.InvalidJvmConfiguration
            observeGradleInitializationScript(failure) == GradleInitializationScriptObservation.UNAVAILABLE ->
                InstalledGradleImportOutcome.InitializationScriptUnavailable
            else -> when (val payload = GradlePayloadClassFileMajor.observe(failure)) {
                is GradlePayloadCompatibility.Unsupported -> InstalledGradleImportOutcome.IncompatiblePayload(payload.major)
                GradlePayloadCompatibility.Unclassified -> InstalledGradleImportOutcome.Failed
            }
        }.also(observer::observe)
    }

private inline fun <reified Failure : Throwable> Throwable?.hasCause(): Boolean =
    generateSequence(this) { current -> current.cause }.take(16)
        .any { current -> current is Failure }

private enum class GradleTaskIdentity { EXACT_WORKSPACE, OTHER }
private enum class GradleTaskKind { PROJECT_RESOLUTION, OTHER }

private sealed interface GradleImportCohort {
    data class Collecting(
        val admitted: Set<ExternalSystemTaskId> = emptySet(),
        val remembered: GradleImportCohortMemory = GradleImportCohortMemory.NoTerminal,
        val admission: GradleImportAdmission = GradleImportAdmission.OPEN,
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
}

private sealed interface GradleImportCohortMemory {
    data object NoTerminal : GradleImportCohortMemory

    sealed interface Terminal : GradleImportCohortMemory {
        val outcome: InstalledGradleImportOutcome
    }

    data object Completed : Terminal {
        override val outcome: InstalledGradleImportOutcome = InstalledGradleImportOutcome.Completed
    }

    data object Cancelled : Terminal {
        override val outcome: InstalledGradleImportOutcome = InstalledGradleImportOutcome.Cancelled
    }

    data object Failed : Terminal {
        override val outcome: InstalledGradleImportOutcome = InstalledGradleImportOutcome.Failed
    }

    fun remember(terminal: GradleImportTerminal): Terminal = when {
        this is Failed || terminal == GradleImportTerminal.FAILED -> Failed
        this is Cancelled || terminal == GradleImportTerminal.CANCELLED -> Cancelled
        else -> Completed
    }

    fun beginReplacement(): GradleImportCohortMemory = when (this) {
        Failed -> Failed
        NoTerminal,
        Completed,
        Cancelled,
            -> NoTerminal
    }
}

private enum class GradleImportAdmission { OPEN, CLOSED }
private enum class GradleTerminalAuthority { EXACT_PATH, ADMITTED_ID }

private fun GradleImportCohort.Collecting.admit(
    id: ExternalSystemTaskId,
): GradleImportCohort.Collecting = when {
    id in admitted -> this
    admitted.isEmpty() && admission == GradleImportAdmission.OPEN -> copy(
        admitted = setOf(id),
        remembered = remembered.beginReplacement(),
    )
    else -> copy(admitted = admitted + id)
}

private fun GradleImportCohort.closeAdmission(): GradleImportCohortTransition = when (this) {
    is GradleImportCohort.Published -> GradleImportCohortTransition.Retained(this)
    is GradleImportCohort.Collecting -> {
        val closed = copy(admission = GradleImportAdmission.CLOSED)
        val terminal = closed.remembered as? GradleImportCohortMemory.Terminal
        if (closed.admitted.isEmpty() && terminal != null) {
            terminal.publish()
        } else {
            GradleImportCohortTransition.Retained(closed)
        }
    }
}

/**
 * Proof transition: `GradleImportCohort + ExternalSystemTaskId + GradleImportTerminal +
 * GradleTerminalAuthority -> GradleImportCohortTransition`.
 *
 * Retained establishes that exact project-open task admission is still open or at least one exact
 * task remains active. Published establishes closed admission, an empty admitted set, and one
 * terminal outcome. A new exact generation admitted after a provisional empty generation replaces
 * completion or cancellation memory before closure while retaining any proven failure. An
 * unadmitted ID-only terminal remains no evidence. Raw IntelliJ callback identity is permitted
 * only at [InstalledGradleImportObserver].
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
            if (remaining.isEmpty() && admission == GradleImportAdmission.CLOSED) {
                terminalEvidence.publish()
            } else {
                GradleImportCohortTransition.Retained(copy(
                    admitted = remaining,
                    remembered = terminalEvidence,
                ))
            }
        }
        authority == GradleTerminalAuthority.EXACT_PATH && admitted.isEmpty() -> {
            val terminalEvidence = remembered.remember(terminal)
            if (admission == GradleImportAdmission.CLOSED) {
                terminalEvidence.publish()
            } else {
                GradleImportCohortTransition.Retained(copy(remembered = terminalEvidence))
            }
        }
        else -> GradleImportCohortTransition.Retained(this)
    }
}

private fun GradleImportCohortMemory.Terminal.publish(): GradleImportCohortTransition.Published {
    return GradleImportCohortTransition.Published(GradleImportCohort.Published(outcome))
}
