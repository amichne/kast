package io.github.amichne.kast.ide.endpoint

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.startup.StartupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.amichne.kast.topology.intellij.HostedWorkspaceColdStartIdentity
import io.github.amichne.kast.topology.intellij.HostedWorkspaceSourceStateSession

/** One eagerly registered IntelliJ project-scoped endpoint coordinator. */
class IdeEndpointService private constructor(
    private val serviceProject: Project?,
    private val coroutineScope: CoroutineScope,
    private val coordinator: IdeEndpointCoordinator,
    private val generations: ProjectEndpointGenerationSource,
    private val coldStart: HostedWorkspaceColdStartIdentity,
) : Disposable {
    private val sourceStates = HostedWorkspaceSourceStateSession(coldStart, this)

    constructor(project: Project, coroutineScope: CoroutineScope) : this(
        project,
        coroutineScope,
        IdeEndpointCoordinator(JdkIdeEndpointPublisher),
        ProjectEndpointGenerationSource(),
        HostedWorkspaceColdStartIdentity.issue(),
    )

    init {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                coordinator.retire(IdeEndpointRetirementCause.SERVICE_CANCELLATION)
            }
        }
    }

    /**
     * Installs cached-readiness signals before the first observation. Project initialization,
     * Gradle import completion, smart-mode entry, and all-startup-activities completion each only
     * trigger a fresh admission; they never wait, import, refresh, or manufacture readiness.
     */
    internal fun start(requestedProject: Project): IdeEndpointServiceStart {
        val project = serviceProject ?: requestedProject
        when (val beginning = coordinator.begin()) {
            IdeEndpointServiceStart.Started -> Unit
            IdeEndpointServiceStart.AlreadyStarted -> return beginning
            is IdeEndpointServiceStart.Rejected -> return beginning
        }
        try {
            val connection = project.messageBus.connect(this)
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
            val startup = StartupManager.getInstance(project)
            startup.runWhenProjectIsInitialized {
                requestAttempt(project)
            }
            startup.allActivitiesPassedFuture.invokeOnCompletion { failure ->
                if (failure == null) {
                    requestAttempt(project)
                }
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
                val startup = LiveIdeEndpointStartup.prepare(
                    project,
                    generations,
                    sourceStates,
                )
                LOG.info("Kast hosted endpoint startup outcome: $startup")
                complete(
                    project,
                    plan.attempt,
                    startup,
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
            IdeEndpointCompletionPlan.Stop -> Unit
            is IdeEndpointCompletionPlan.Retry -> executeSignalPlan(
                project,
                IdeEndpointSignalPlan.Launch(plan.attempt),
            )
            is IdeEndpointCompletionPlan.RetryAfter -> coroutineScope.launch {
                delay(plan.retry.cadence.duration)
                executeSignalPlan(project, coordinator.planRetry(plan.retry))
            }
            is IdeEndpointCompletionPlan.Activate -> when (
                val activationPlan = coordinator.activate(plan.request)
            ) {
                is IdeEndpointActivationPlan.Serve -> try {
                    activationPlan.endpoint.serveUntilClosed()
                } finally {
                    coordinator.retire(IdeEndpointRetirementCause.SERVING_TERMINATED)
                }
                is IdeEndpointActivationPlan.Retired -> Unit
                IdeEndpointActivationPlan.Stop -> Unit
            }
        }
    }

    override fun dispose() {
        coordinator.retire(IdeEndpointRetirementCause.PROJECT_OR_PLUGIN_DISPOSAL)
    }

    internal companion object {
        private val LOG = Logger.getInstance(IdeEndpointService::class.java)

        @JvmSynthetic
        fun testing(
            coroutineScope: CoroutineScope,
            coordinator: IdeEndpointCoordinator,
        ): IdeEndpointService = IdeEndpointService(
            null,
            coroutineScope,
            coordinator,
            ProjectEndpointGenerationSource(),
            HostedWorkspaceColdStartIdentity.issue(),
        )
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
