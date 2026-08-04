package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.transition.GenerationPublication
import io.github.amichne.kast.idea.transition.TransitionRun
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceTransitionCoordinator
import io.github.amichne.kast.idea.transition.WorkspaceTransitionOperations
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.idea.transition.WorkspaceWakeup

internal class WorkspaceTransitionWorker(
    initialConfig: KastConfig,
    private val semanticAdmission: IdeaIndexSemanticAdmission,
    private val eventWakeup: WorkspaceEventWakeup,
    private val refreshWorkspace: (Set<WorkspaceSignal>) -> Unit,
    private val loadLiveConfig: (KastConfig) -> KastConfig,
    private val resolveIdentity: (KastConfig) -> WorkspaceStateIdentity,
    private val runIndexingPass: (KastConfig) -> IndexingPassResult,
    private val publishWorkspaceGeneration: (WorkspaceStateIdentity) -> Unit,
    private val waitForNextPass: ((Long) -> Boolean)?,
    private val isCancelled: () -> Boolean,
    private val onConfigFallback: (Throwable) -> Unit,
    private val onCompleted: (KastSourceIndexSummary) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    onTransition: (WorkspaceTransitionSnapshot) -> Unit,
) {
    private var lastValidConfig = initialConfig
    private var cycleConfig = initialConfig
    private var cycleResult: IndexingPassResult? = null
    private var reconciliationToken: IdeaIndexSemanticAdmission.ReconciliationToken? = null
    private var publishedSummary: KastSourceIndexSummary? = null
    private var consecutiveFailures = 0

    private val coordinator = WorkspaceTransitionCoordinator(
        operations = object : WorkspaceTransitionOperations {
            override fun settle(signals: Set<WorkspaceSignal>) {
                if (!eventWakeup.awaitQuiescence(EVENT_QUIESCENCE_MILLIS)) {
                    throw InterruptedException("Workspace transition settlement was interrupted")
                }
            }

            override fun refresh(signals: Set<WorkspaceSignal>) {
                semanticAdmission.dirty("workspace transition is refreshing semantic inputs")
                cycleResult = null
                reconciliationToken = null
                refreshWorkspace(signals)
                cycleConfig = try {
                    loadLiveConfig(lastValidConfig)
                } catch (failure: Exception) {
                    onConfigFallback(failure)
                    lastValidConfig
                }
            }

            override fun captureIdentity(): WorkspaceStateIdentity = resolveIdentity(cycleConfig)

            override fun reconcile(candidate: WorkspaceStateIdentity): WorkspaceStateIdentity {
                reconciliationToken = semanticAdmission.beginReconciliation(
                    "workspace reconciliation is active",
                )
                val attempted = runCatching { runIndexingPass(cycleConfig) }
                val scopeFailure = attempted.exceptionOrNull() as? IndexingScopeConfigurationException
                val reconciledIdentity = if (scopeFailure != null && cycleConfig != lastValidConfig) {
                    onConfigFallback(scopeFailure)
                    cycleConfig = lastValidConfig
                    resolveIdentity(cycleConfig).also {
                        cycleResult = runIndexingPass(cycleConfig)
                    }
                } else {
                    cycleResult = attempted.getOrThrow()
                    candidate
                }
                cycleResult?.graphFailure?.let { throw it }
                return reconciledIdentity
            }

            override fun publish(generation: io.github.amichne.kast.idea.transition.PublishedWorkspaceGeneration):
                GenerationPublication {
                val result = checkNotNull(cycleResult) { "Verified transition has no indexing result" }
                val token = checkNotNull(reconciliationToken) { "Verified transition has no admission token" }
                val admitted = semanticAdmission.publishReady(token) {
                    publishWorkspaceGeneration(generation.identity)
                }
                if (!admitted) return GenerationPublication.Invalidated
                lastValidConfig = cycleConfig
                publishedSummary = result.summary
                return GenerationPublication.Published
            }
        },
        onTransition = onTransition,
        onBlocked = { _, failure ->
            semanticAdmission.fail(failure.message?.takeIf(String::isNotBlank) ?: failure::class.java.name)
            onFailure(failure)
        },
    )

    fun observe(signal: WorkspaceSignal) {
        coordinator.observe(signal)
    }

    fun requestRecoveryAudit() {
        semanticAdmission.dirty("workspace recovery audit requires verification")
        coordinator.observe(WorkspaceSignal.RecoveryAudit)
    }

    fun run() {
        while (!isCancelled()) {
            when (coordinator.reconcilePending()) {
                TransitionRun.NoWork -> {
                    if (!awaitWork(RECOVERY_AUDIT_MILLIS)) return
                }

                TransitionRun.Published -> {
                    consecutiveFailures = 0
                    onCompleted(checkNotNull(publishedSummary))
                    publishedSummary = null
                    if (!awaitWork(RECOVERY_AUDIT_MILLIS)) return
                }

                TransitionRun.Invalidated -> Unit

                TransitionRun.Blocked -> {
                    consecutiveFailures += 1
                    if (!awaitWork(indexingRetryDelayMillis(consecutiveFailures))) return
                }
            }
        }
    }

    private fun awaitWork(delayMillis: Long): Boolean {
        waitForNextPass?.let { wait ->
            if (!wait(delayMillis)) return false
            if (coordinator.snapshot().pendingSignals.isEmpty()) requestRecoveryAudit()
            return true
        }
        return when (eventWakeup.awaitWakeup(delayMillis)) {
            WorkspaceWakeup.Signal -> true
            WorkspaceWakeup.RecoveryAudit -> {
                if (coordinator.snapshot().pendingSignals.isEmpty()) requestRecoveryAudit()
                true
            }

            WorkspaceWakeup.Interrupted -> false
        }
    }
}

private const val EVENT_QUIESCENCE_MILLIS = 250L
