package io.github.amichne.kast.ide.endpoint

import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.startup.StartupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private sealed interface IdeEndpointCoordinatorState {
    data object Unstarted : IdeEndpointCoordinatorState
    data object InstallingListeners : IdeEndpointCoordinatorState
    data object InstallingListenersWithPendingSignal : IdeEndpointCoordinatorState
    data class AwaitingDeferred(
        val last: IdeEndpointDeferredReadiness,
    ) : IdeEndpointCoordinatorState
    data class Attempting(val attempt: IssuedIdeEndpointAttempt) : IdeEndpointCoordinatorState
    data class AttemptingWithPendingSignal(
        val attempt: IssuedIdeEndpointAttempt,
    ) : IdeEndpointCoordinatorState
    data class AwaitingActivation(
        val request: IssuedIdeEndpointActivationRequest,
    ) : IdeEndpointCoordinatorState
    data class Publishing(
        val claim: IssuedIdeEndpointPublicationClaim,
    ) : IdeEndpointCoordinatorState
    data class Ready(val endpoint: ReadyIdeEndpoint) : IdeEndpointCoordinatorState
    data class Rejected(val failure: IdeEndpointCoordinatorFailure) : IdeEndpointCoordinatorState
}

internal sealed interface IdeEndpointCoordinatorFailure {
    data class Startup(val cause: IdeEndpointStartupFailure) : IdeEndpointCoordinatorFailure
    data class Publication(val cause: IdeEndpointPublicationFailure) : IdeEndpointCoordinatorFailure
    data object ListenerInstallationFailed : IdeEndpointCoordinatorFailure
}

internal sealed interface IdeEndpointServiceStart {
    data object Started : IdeEndpointServiceStart
    data object AlreadyStarted : IdeEndpointServiceStart
    data class Rejected(val failure: IdeEndpointCoordinatorFailure) : IdeEndpointServiceStart
}

internal sealed interface IdeEndpointAttempt
private class IssuedIdeEndpointAttempt : IdeEndpointAttempt

internal sealed interface IdeEndpointSignalPlan {
    data class Launch(val attempt: IdeEndpointAttempt) : IdeEndpointSignalPlan
    data object Coalesced : IdeEndpointSignalPlan
    data object Terminal : IdeEndpointSignalPlan
}

internal sealed interface IdeEndpointActivationRequest
private class IssuedIdeEndpointActivationRequest(
    val endpoint: PreparedIdeEndpoint,
) : IdeEndpointActivationRequest
private class IssuedIdeEndpointPublicationClaim

internal sealed interface IdeEndpointCompletionPlan {
    data object Await : IdeEndpointCompletionPlan
    data object Retry : IdeEndpointCompletionPlan
    data class Activate(val request: IdeEndpointActivationRequest) : IdeEndpointCompletionPlan
    data object Stop : IdeEndpointCompletionPlan
}

internal sealed interface IdeEndpointActivationPlan {
    data class Serve(val endpoint: ReadyIdeEndpoint) : IdeEndpointActivationPlan
    data object Stop : IdeEndpointActivationPlan
}

private sealed interface IdeEndpointActivationClaim {
    data class Claimed(
        val endpoint: PreparedIdeEndpoint,
        val publication: IssuedIdeEndpointPublicationClaim,
    ) : IdeEndpointActivationClaim
    data object Rejected : IdeEndpointActivationClaim
}

private enum class IdeEndpointPendingSignal {
    ABSENT,
    PRESENT,
}

/** Pure single-flight coordinator; endpoint publication always runs outside its monitor. */
internal class IdeEndpointCoordinator(
    publisher: IdeEndpointPublisher,
) {
    private val owner = IdeEndpointOwner(publisher)
    private var state: IdeEndpointCoordinatorState = IdeEndpointCoordinatorState.Unstarted

    @Synchronized
    fun begin(): IdeEndpointServiceStart = if (state == IdeEndpointCoordinatorState.Unstarted) {
        state = IdeEndpointCoordinatorState.InstallingListeners
        IdeEndpointServiceStart.Started
    } else {
        IdeEndpointServiceStart.AlreadyStarted
    }

    @Synchronized
    fun rejectListenerInstallation(): IdeEndpointServiceStart {
        val failure = IdeEndpointCoordinatorFailure.ListenerInstallationFailed
        return when (state) {
            IdeEndpointCoordinatorState.InstallingListeners,
            IdeEndpointCoordinatorState.InstallingListenersWithPendingSignal,
            -> {
                state = IdeEndpointCoordinatorState.Rejected(failure)
                IdeEndpointServiceStart.Rejected(failure)
            }
            else -> IdeEndpointServiceStart.AlreadyStarted
        }
    }

    /**
     * Proof transition: `IdeEndpointCoordinatorState -> IdeEndpointSignalPlan`.
     *
     * Preserves signals received during listener installation and issues at most one attempt for
     * an admitted ready-to-attempt state. Closed non-launch outcomes are [IdeEndpointSignalPlan].
     * No raw state leaves this coordinator boundary.
    */
    @Synchronized
    fun planSignal(): IdeEndpointSignalPlan = when (val current = state) {
        IdeEndpointCoordinatorState.InstallingListeners -> {
            state = IdeEndpointCoordinatorState.InstallingListenersWithPendingSignal
            IdeEndpointSignalPlan.Coalesced
        }
        IdeEndpointCoordinatorState.InstallingListenersWithPendingSignal ->
            IdeEndpointSignalPlan.Coalesced
        is IdeEndpointCoordinatorState.AwaitingDeferred,
        -> {
            val attempt = IssuedIdeEndpointAttempt()
            state = IdeEndpointCoordinatorState.Attempting(attempt)
            IdeEndpointSignalPlan.Launch(attempt)
        }
        is IdeEndpointCoordinatorState.Attempting -> {
            state = IdeEndpointCoordinatorState.AttemptingWithPendingSignal(current.attempt)
            IdeEndpointSignalPlan.Coalesced
        }
        is IdeEndpointCoordinatorState.AttemptingWithPendingSignal ->
            IdeEndpointSignalPlan.Coalesced
        IdeEndpointCoordinatorState.Unstarted,
        is IdeEndpointCoordinatorState.AwaitingActivation,
        is IdeEndpointCoordinatorState.Publishing,
        is IdeEndpointCoordinatorState.Ready,
        is IdeEndpointCoordinatorState.Rejected,
        -> IdeEndpointSignalPlan.Terminal
    }

    /**
     * Proof transition: `IdeEndpointCoordinatorState -> IdeEndpointSignalPlan`.
     *
     * Establishes that all listener registrations completed before issuing the sole initial
     * [IdeEndpointAttempt]. Unexpected lifecycle states close to [IdeEndpointSignalPlan.Terminal].
     * No raw listener state leaves this coordinator boundary.
     */
    @Synchronized
    fun listenersInstalled(): IdeEndpointSignalPlan = when (state) {
        IdeEndpointCoordinatorState.InstallingListeners,
        IdeEndpointCoordinatorState.InstallingListenersWithPendingSignal,
        -> {
            val attempt = IssuedIdeEndpointAttempt()
            state = IdeEndpointCoordinatorState.Attempting(attempt)
            IdeEndpointSignalPlan.Launch(attempt)
        }
        else -> IdeEndpointSignalPlan.Terminal
    }

    /**
     * Proof transition: `(IdeEndpointAttempt, IdeEndpointStartup) -> IdeEndpointCompletionPlan`.
     *
     * Accepts only the currently issued attempt capability and preserves pending readiness as a
     * retry or a typed activation request. Stale and invalid-state completions close to
     * [IdeEndpointCompletionPlan.Stop]. No raw coordinator state leaves this boundary.
     */
    @Synchronized
    fun planCompletion(
        attempt: IdeEndpointAttempt,
        startup: IdeEndpointStartup,
    ): IdeEndpointCompletionPlan {
        val pendingSignal = when (val current = state) {
            is IdeEndpointCoordinatorState.Attempting -> {
                if (current.attempt !== attempt) return IdeEndpointCompletionPlan.Stop
                IdeEndpointPendingSignal.ABSENT
            }
            is IdeEndpointCoordinatorState.AttemptingWithPendingSignal -> {
                if (current.attempt !== attempt) return IdeEndpointCompletionPlan.Stop
                IdeEndpointPendingSignal.PRESENT
            }
            else -> return IdeEndpointCompletionPlan.Stop
        }
        return when (startup) {
            is IdeEndpointStartup.Deferred -> if (
                pendingSignal == IdeEndpointPendingSignal.PRESENT
            ) {
                state = IdeEndpointCoordinatorState.AwaitingDeferred(startup.readiness)
                IdeEndpointCompletionPlan.Retry
            } else {
                state = IdeEndpointCoordinatorState.AwaitingDeferred(startup.readiness)
                IdeEndpointCompletionPlan.Await
            }
            is IdeEndpointStartup.Rejected -> {
                state = IdeEndpointCoordinatorState.Rejected(
                    IdeEndpointCoordinatorFailure.Startup(startup.failure),
                )
                IdeEndpointCompletionPlan.Stop
            }
            is IdeEndpointStartup.Prepared -> {
                val request = IssuedIdeEndpointActivationRequest(startup.endpoint)
                state = IdeEndpointCoordinatorState.AwaitingActivation(request)
                IdeEndpointCompletionPlan.Activate(request)
            }
        }
    }

    /**
     * Proof transition: `IdeEndpointActivationRequest -> IdeEndpointActivationPlan`.
     *
     * Accepts only the currently issued request and performs publication outside this coordinator's
     * monitor. Stale requests close to [IdeEndpointActivationPlan.Stop]. No raw endpoint state
     * leaves this boundary.
     */
    fun activate(request: IdeEndpointActivationRequest): IdeEndpointActivationPlan =
        when (val claim = claimActivation(request)) {
            is IdeEndpointActivationClaim.Claimed -> planActivation(
                claim.publication,
                owner.publish(claim.endpoint),
            )
            IdeEndpointActivationClaim.Rejected -> IdeEndpointActivationPlan.Stop
        }

    /**
     * Proof transition: `IdeEndpointActivationRequest -> IdeEndpointActivationClaim`.
     *
     * Refines only the currently issued request into the sole publication capability. A stale or
     * wrong-state request closes to [IdeEndpointActivationClaim.Rejected]. No raw endpoint state
     * leaves this coordinator boundary.
     */
    @Synchronized
    private fun claimActivation(request: IdeEndpointActivationRequest): IdeEndpointActivationClaim {
        val current = state
        if (current !is IdeEndpointCoordinatorState.AwaitingActivation ||
            current.request !== request
        ) return IdeEndpointActivationClaim.Rejected
        val publication = IssuedIdeEndpointPublicationClaim()
        state = IdeEndpointCoordinatorState.Publishing(publication)
        return IdeEndpointActivationClaim.Claimed(current.request.endpoint, publication)
    }

    /**
     * Proof transition: `(IssuedIdeEndpointPublicationClaim, IdeEndpointActivation) ->
     * IdeEndpointActivationPlan`.
     *
     * Accepts a publication result only for the still-current claim and preserves a ready endpoint
     * or finite publication rejection. Stale results close to [IdeEndpointActivationPlan.Stop].
     * No raw publication result leaves this coordinator boundary.
     */
    @Synchronized
    private fun planActivation(
        claim: IssuedIdeEndpointPublicationClaim,
        activation: IdeEndpointActivation,
    ): IdeEndpointActivationPlan {
        val current = state
        if (current !is IdeEndpointCoordinatorState.Publishing || current.claim !== claim) {
            return IdeEndpointActivationPlan.Stop
        }
        return when (activation) {
            is IdeEndpointActivation.Ready -> {
                state = IdeEndpointCoordinatorState.Ready(activation.endpoint)
                IdeEndpointActivationPlan.Serve(activation.endpoint)
            }
            is IdeEndpointActivation.Rejected -> {
                state = IdeEndpointCoordinatorState.Rejected(
                    IdeEndpointCoordinatorFailure.Publication(activation.failure),
                )
                IdeEndpointActivationPlan.Stop
            }
        }
    }
}

/** One eagerly registered IntelliJ project-scoped endpoint coordinator. */
class IdeEndpointService(
    private val coroutineScope: CoroutineScope,
) {
    private val coordinator = IdeEndpointCoordinator(JdkIdeEndpointPublisher)
    private val generations = ProjectEndpointGenerationSource()

    /**
     * Installs cached-readiness signals before the first observation. Each signal only triggers a
     * fresh admission; it never waits, imports, refreshes, or manufactures readiness.
     */
    internal fun start(project: Project): IdeEndpointServiceStart {
        when (val beginning = coordinator.begin()) {
            IdeEndpointServiceStart.Started -> Unit
            IdeEndpointServiceStart.AlreadyStarted -> return beginning
            is IdeEndpointServiceStart.Rejected -> return beginning
        }
        try {
            val connection = project.messageBus.connect(project)
            connection.subscribe(
                ProjectDataImportListener.TOPIC,
                object : ProjectDataImportListener {
                    override fun onImportFinished(projectPath: String?) = requestAttempt(project)
                    override fun onFinalTasksFinished(projectPath: String?) = requestAttempt(project)
                },
            )
            connection.subscribe(
                DumbService.DUMB_MODE,
                object : DumbService.DumbModeListener {
                    override fun exitDumbMode() = requestAttempt(project)
                },
            )
            StartupManager.getInstance(project).runWhenProjectIsInitialized {
                requestAttempt(project)
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return coordinator.rejectListenerInstallation()
        }
        executeSignalPlan(project, coordinator.listenersInstalled())
        return IdeEndpointServiceStart.Started
    }

    private fun requestAttempt(project: Project) {
        executeSignalPlan(project, coordinator.planSignal())
    }

    private fun executeSignalPlan(
        project: Project,
        plan: IdeEndpointSignalPlan,
    ) {
        when (plan) {
            is IdeEndpointSignalPlan.Launch -> coroutineScope.launch(Dispatchers.IO) {
                complete(
                    project,
                    plan.attempt,
                    LiveIdeEndpointStartup.prepare(project, generations),
                )
            }
            IdeEndpointSignalPlan.Coalesced,
            IdeEndpointSignalPlan.Terminal,
            -> Unit
        }
    }

    private suspend fun complete(
        project: Project,
        attempt: IdeEndpointAttempt,
        startup: IdeEndpointStartup,
    ) {
        when (val plan = coordinator.planCompletion(attempt, startup)) {
            IdeEndpointCompletionPlan.Await,
            IdeEndpointCompletionPlan.Stop,
            -> Unit
            IdeEndpointCompletionPlan.Retry -> requestAttempt(project)
            is IdeEndpointCompletionPlan.Activate -> when (
                val activationPlan = coordinator.activate(plan.request)
            ) {
                is IdeEndpointActivationPlan.Serve -> activationPlan.endpoint.serveUntilClosed()
                IdeEndpointActivationPlan.Stop -> Unit
            }
        }
    }

}

/** Routes one already-open IntelliJ Project into its scoped endpoint service. */
class IdeEndpointProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        when (project.service<IdeEndpointService>().start(project)) {
            IdeEndpointServiceStart.Started,
            IdeEndpointServiceStart.AlreadyStarted,
            is IdeEndpointServiceStart.Rejected,
            -> Unit
        }
    }
}
