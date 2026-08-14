package io.github.amichne.kast.idea.backend.semantic

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.workspace.spi.EdtHeartbeatTimeout
import io.github.amichne.kast.workspace.spi.RuntimeLivenessAdmission
import io.github.amichne.kast.workspace.spi.RuntimeLivenessAuthority
import io.github.amichne.kast.workspace.spi.RuntimeLivenessFailure
import io.github.amichne.kast.workspace.spi.SemanticReadFreshness
import io.github.amichne.kast.workspace.spi.SemanticReadFreshnessAuthority
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal interface WorkspaceSemanticReadAuthority {
    fun status(): IdeaIndexSemanticAdmission.Status

    fun openRead(): IdeaIndexSemanticAdmission.WorkspaceReadToken

    fun isReadCurrent(token: IdeaIndexSemanticAdmission.WorkspaceReadToken): Boolean

    fun isReconciliationCurrent(token: IdeaIndexSemanticAdmission.ReconciliationToken): Boolean
}

internal enum class IdeaRuntimeObservation {
    Available,
    Disposed,
}

internal enum class IdeaEdtHeartbeatObservation {
    Responded,
    TimedOut,
    Interrupted,
    Unavailable,
}

internal fun interface IdeaEdtHeartbeat {
    fun await(timeout: EdtHeartbeatTimeout): IdeaEdtHeartbeatObservation
}

internal class IdeaRuntimeLivenessAuthority(
    private val runtime: () -> IdeaRuntimeObservation,
    private val heartbeat: IdeaEdtHeartbeat,
    private val timeout: EdtHeartbeatTimeout,
) : RuntimeLivenessAuthority {
    constructor(project: Project) : this(
        runtime = {
            if (project.isDisposed) IdeaRuntimeObservation.Disposed else IdeaRuntimeObservation.Available
        },
        heartbeat = productionEdtHeartbeat(),
        timeout = EdtHeartbeatTimeout.standard(),
    )

    /**
     * Proof transition: <code>IntelliJ runtime observation -> RuntimeLivenessAdmission</code>.
     *
     * Establishes that the exact project is not disposed and its event-dispatch thread responded
     * within [timeout]. [RuntimeLivenessFailure] is the closed expected failure. Raw IntelliJ
     * project state and timed waiting remain inside this adapter.
     */
    override fun admit(): RuntimeLivenessAdmission {
        if (runtime() == IdeaRuntimeObservation.Disposed) {
            return RuntimeLivenessAdmission.Rejected(RuntimeLivenessFailure.RuntimeDisposed)
        }
        return when (heartbeat.await(timeout)) {
            IdeaEdtHeartbeatObservation.Responded -> RuntimeLivenessAdmission.Live
            IdeaEdtHeartbeatObservation.TimedOut ->
                RuntimeLivenessAdmission.Rejected(
                    RuntimeLivenessFailure.FrozenEventDispatchThread(timeout),
                )
            IdeaEdtHeartbeatObservation.Interrupted ->
                RuntimeLivenessAdmission.Rejected(RuntimeLivenessFailure.ProbeInterrupted)
            IdeaEdtHeartbeatObservation.Unavailable ->
                RuntimeLivenessAdmission.Rejected(RuntimeLivenessFailure.ProbeUnavailable)
        }
    }
}

internal enum class IdeaDumbModeObservation {
    Smart,
    Dumb,
}

internal class IdeaSemanticReadFreshnessAuthority(
    private val dumbMode: () -> IdeaDumbModeObservation,
    private val semanticStatus: () -> IdeaIndexSemanticAdmission.Status,
) : SemanticReadFreshnessAuthority {
    constructor(
        project: Project,
        semanticAuthority: WorkspaceSemanticReadAuthority,
    ) : this(
        dumbMode = {
            if (DumbService.isDumb(project)) IdeaDumbModeObservation.Dumb else IdeaDumbModeObservation.Smart
        },
        semanticStatus = semanticAuthority::status,
    )

    /**
     * Proof transition:
     * <code>(IntelliJ dumb-mode observation, semantic publication status) -> SemanticReadFreshness</code>.
     *
     * Establishes a closed source-freshness state without conflating it with runtime, relation, or
     * graph readiness. Raw IntelliJ and legacy admission observations remain inside this adapter.
     */
    override fun observe(): SemanticReadFreshness {
        if (dumbMode() == IdeaDumbModeObservation.Dumb) return SemanticReadFreshness.DumbMode
        return when (semanticStatus()) {
            is IdeaIndexSemanticAdmission.Status.Ready -> SemanticReadFreshness.Ready
            is IdeaIndexSemanticAdmission.Status.Pending -> SemanticReadFreshness.TransitionInProgress
            is IdeaIndexSemanticAdmission.Status.Failed -> SemanticReadFreshness.WorkspaceBlocked
        }
    }
}

private fun productionEdtHeartbeat(): IdeaEdtHeartbeat = IdeaEdtHeartbeat { timeout ->
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) {
        IdeaEdtHeartbeatObservation.Responded
    } else {
        val response = CountDownLatch(1)
        try {
            application.invokeLater(response::countDown, ModalityState.any())
            if (response.await(timeout.milliseconds, TimeUnit.MILLISECONDS)) {
                IdeaEdtHeartbeatObservation.Responded
            } else {
                IdeaEdtHeartbeatObservation.TimedOut
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            IdeaEdtHeartbeatObservation.Interrupted
        } catch (_: RuntimeException) {
            IdeaEdtHeartbeatObservation.Unavailable
        }
    }
}
