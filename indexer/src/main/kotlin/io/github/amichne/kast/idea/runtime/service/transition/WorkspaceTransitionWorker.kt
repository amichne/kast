package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.transition.GenerationPublication
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionGuard
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionInProgressException
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionInspectionException
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionStatus
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.OpenWorkspacePublication
import io.github.amichne.kast.idea.transition.PreparedWorkspacePublication
import io.github.amichne.kast.idea.transition.TransitionRun
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceTransitionCoordinator
import io.github.amichne.kast.idea.transition.WorkspaceTransitionOperations
import io.github.amichne.kast.idea.transition.WorkspaceTransitionRequest
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.idea.transition.WorkspaceWakeup
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.GraphEvidenceBlocker
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import java.util.concurrent.CancellationException
import java.time.Duration

internal class WorkspaceTransitionWorker(
    initialConfig: KastConfig,
    initialModelBuildSemanticIdentity: BuildSemanticInputIdentity,
    private val resolveBuildSemanticInputIdentity: () -> BuildSemanticInputIdentity,
    private val semanticAdmission: IdeaIndexSemanticAdmission,
    private val eventWakeup: WorkspaceEventWakeup,
    private val gitWorktreeTransitionGuard: GitWorktreeTransitionGuard = GitWorktreeTransitionGuard.stable(),
    private val refreshWorkspace: (Set<WorkspaceSignal>) -> Unit,
    private val loadLiveConfig: (KastConfig) -> KastConfig,
    private val captureCandidate: (KastConfig, BuildSemanticInputIdentity) -> WorkspaceReconciliationCandidate,
    private val runIndexingPass:
        (KastConfig, WorkspaceReconciliationCandidate, IdeaIndexSemanticAdmission.ReconciliationToken) -> IndexingPassResult,
    private val workspaceGenerationPublication: WorkspaceGenerationPublication,
    private val waitForNextPass: ((Long) -> Boolean)?,
    private val isCancelled: () -> Boolean,
    private val onConfigFallback: (Throwable) -> Unit,
    private val onCompleted: (CompletedWorkspaceReconciliation) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    onTransition: (WorkspaceTransitionSnapshot) -> Unit,
) {
    private var lastValidConfig = initialConfig
    private var cycleConfig = initialConfig
    private var cycleCandidate: WorkspaceReconciliationCandidate? = null
    private var cycleResult: IndexingPassResult? = null
    private var reconciliationToken: IdeaIndexSemanticAdmission.ReconciliationToken? = null
    private var publishedReconciliation: PendingCompletedWorkspaceReconciliation =
        PendingCompletedWorkspaceReconciliation.Absent
    private var consecutiveFailures = ConsecutiveIndexingFailures.none()
    private var modelBuildSemanticIdentity = initialModelBuildSemanticIdentity

    private val coordinator = WorkspaceTransitionCoordinator(
        operations = object : WorkspaceTransitionOperations {
            override fun settle(signals: Set<WorkspaceSignal>) {
                if (!eventWakeup.awaitQuiescence(EVENT_QUIESCENCE_MILLIS)) {
                    throw InterruptedException("Workspace transition settlement was interrupted")
                }
                requireStableGitWorktreeTransition()
            }

            override fun refresh(signals: Set<WorkspaceSignal>) {
                requireStableGitWorktreeTransition()
                semanticAdmission.dirty("workspace transition is refreshing semantic inputs")
                cycleCandidate = null
                cycleResult = null
                reconciliationToken = null
                publishedReconciliation = PendingCompletedWorkspaceReconciliation.Absent
                val buildInputsBeforeRefresh = resolveBuildSemanticInputIdentity()
                val requiresGradleRefresh = WorkspaceSignal.BuildSemantic in signals ||
                    WorkspaceSignal.RecoveryAudit in signals ||
                    buildInputsBeforeRefresh != modelBuildSemanticIdentity
                val effectiveSignals = if (requiresGradleRefresh) {
                    signals + WorkspaceSignal.BuildSemantic
                } else {
                    signals
                }
                refreshWorkspace(effectiveSignals)
                if (requiresGradleRefresh) {
                    val buildInputsAfterRefresh = resolveBuildSemanticInputIdentity()
                    if (buildInputsAfterRefresh != buildInputsBeforeRefresh) {
                        throw BuildSemanticInputsMovedDuringRefreshException(
                            before = buildInputsBeforeRefresh,
                            after = buildInputsAfterRefresh,
                        )
                    }
                    modelBuildSemanticIdentity = buildInputsAfterRefresh
                }
                cycleConfig = try {
                    loadLiveConfig(lastValidConfig)
                } catch (failure: Exception) {
                    onConfigFallback(failure)
                    lastValidConfig
                }
                requireStableGitWorktreeTransition()
            }

            override fun captureIdentity(): WorkspaceStateIdentity {
                val currentBuildInputs = currentImportedBuildInputs()
                val captured = captureCandidate(cycleConfig, currentBuildInputs)
                if (reconciliationToken == null) cycleCandidate = captured
                return captured.identity
            }

            override fun reconcile(candidate: WorkspaceStateIdentity): WorkspaceStateIdentity {
                reconciliationToken = semanticAdmission.beginReconciliation(
                    "workspace reconciliation is active",
                )
                val candidateInputs = checkNotNull(cycleCandidate) { "Workspace candidate was not captured" }
                val token = checkNotNull(reconciliationToken)
                val attempted = runCatching { runIndexingPass(cycleConfig, candidateInputs, token) }
                val scopeFailure = attempted.exceptionOrNull() as? IndexingScopeConfigurationException
                val reconciledIdentity = if (scopeFailure != null && cycleConfig != lastValidConfig) {
                    onConfigFallback(scopeFailure)
                        cycleConfig = lastValidConfig
                        captureCandidate(cycleConfig, currentImportedBuildInputs()).also { fallback ->
                            cycleCandidate = fallback
                            cycleResult = runIndexingPass(cycleConfig, fallback, token)
                    }.identity
                } else {
                    cycleResult = attempted.getOrThrow()
                    candidate
                }
                requireActive()
                return reconciledIdentity
            }

            override fun beginPublication(): OpenWorkspacePublication {
                requireActive()
                return workspaceGenerationPublication.begin()
            }

            override fun preparePublication(
                open: OpenWorkspacePublication,
                identity: WorkspaceStateIdentity,
            ): PreparedWorkspacePublication {
                requireActive()
                val graphBlocker = when (checkNotNull(cycleResult).graphOutcome) {
                    GraphLaneOutcome.Committed -> null
                    is GraphLaneOutcome.Blocked -> GraphEvidenceBlocker.INDEXING_FAILED
                }
                return workspaceGenerationPublication.prepare(open, identity, graphBlocker)
            }

            override fun commitPublication(prepared: PreparedWorkspacePublication): GenerationPublication {
                requireActive()
                requireStableGitWorktreeTransition()
                val result = checkNotNull(cycleResult) { "Verified transition has no indexing result" }
                val token = checkNotNull(reconciliationToken) { "Verified transition has no admission token" }
                return when (val publication = semanticAdmission.publishReady(token) {
                    requireStableGitWorktreeTransition()
                    workspaceGenerationPublication.commit(prepared)
                }) {
                    is IdeaIndexSemanticAdmission.ReadyPublication.Admitted -> {
                        lastValidConfig = cycleConfig
                        publishedReconciliation = PendingCompletedWorkspaceReconciliation.Available(
                            CompletedWorkspaceReconciliation(
                                summary = result.summary,
                                snapshotPublication = checkNotNull(cycleCandidate).snapshotPublication,
                            ),
                        )
                        GenerationPublication.Published(publication.commit)
                    }

                    IdeaIndexSemanticAdmission.ReadyPublication.InvalidatedBeforeCommit ->
                        GenerationPublication.InvalidatedBeforeCommit

                    is IdeaIndexSemanticAdmission.ReadyPublication.InvalidatedAfterCommit ->
                        GenerationPublication.InvalidatedAfterCommit(publication.commit)
                }
            }

            override fun discardPublication(open: OpenWorkspacePublication) {
                workspaceGenerationPublication.discard(open)
            }

            override fun discardPublication(prepared: PreparedWorkspacePublication) {
                workspaceGenerationPublication.discard(prepared)
            }
        },
        initialPublished = workspaceGenerationPublication.current(),
        onTransition = onTransition,
        onBlocked = { _, failure ->
            semanticAdmission.fail(failure.message?.takeIf(String::isNotBlank) ?: failure::class.java.name)
            onFailure(failure)
        },
    )

    fun observe(signal: WorkspaceSignal) = coordinator.observe(signal)

    fun observe(request: WorkspaceTransitionRequest) = coordinator.observe(request)

    fun requestRecoveryAudit() {
        val audit = try {
            semanticAdmission.beginRecoveryAudit("periodic recovery audit is verifying workspace identity")
        } catch (failure: Throwable) {
            rethrowRecoveryAuditCancellation(failure)
            requestRecoveryTransition(RecoveryAuditOutcome.WorkspaceDrift)
            return
        }
        when (val outcome = recoveryAuditOutcome(audit.generation)) {
            RecoveryAuditOutcome.Current -> {
                requireActive()
                when (semanticAdmission.restoreReadyAfterRecoveryAudit(audit)) {
                    is IdeaIndexSemanticAdmission.RecoveryAuditRestoration.Restored -> return
                    IdeaIndexSemanticAdmission.RecoveryAuditRestoration.Invalidated ->
                        requestRecoveryTransition(RecoveryAuditOutcome.WorkspaceDrift)
                }
            }

            is RecoveryAuditOutcome.Drift -> requestRecoveryTransition(outcome)
        }
    }

    fun requestInitialReconciliation() {
        semanticAdmission.dirty("initial workspace reconciliation is required")
        coordinator.observe(WorkspaceSignal.BuildSemantic)
    }

    fun run() {
        while (!isCancelled()) {
            when (coordinator.reconcilePending()) {
                TransitionRun.NoWork -> {
                    if (awaitWork(RECOVERY_AUDIT_DELAY) == WorkspaceWorkerWaitOutcome.Interrupted) return
                }

                TransitionRun.Published -> {
                    consecutiveFailures = ConsecutiveIndexingFailures.none()
                    onCompleted(publishedReconciliation.requireCompletion())
                    publishedReconciliation = PendingCompletedWorkspaceReconciliation.Absent
                    if (awaitWork(RECOVERY_AUDIT_DELAY) == WorkspaceWorkerWaitOutcome.Interrupted) return
                }

                TransitionRun.Invalidated -> Unit

                TransitionRun.Retry -> {
                    if (awaitWork(GIT_TRANSITION_RETRY_DELAY) == WorkspaceWorkerWaitOutcome.Interrupted) return
                }

                TransitionRun.Blocked -> {
                    consecutiveFailures = consecutiveFailures.afterFailure()
                    if (awaitWork(consecutiveFailures.retryDelay) == WorkspaceWorkerWaitOutcome.Interrupted) return
                }
            }
        }
    }

    private fun recoveryAuditOutcome(
        expectedPublished: PublishedWorkspaceGenerationManifest,
    ): RecoveryAuditOutcome {
        return try {
            requireActive()
            val published = when (val current = workspaceGenerationPublication.current()) {
                PublishedWorkspaceGenerationState.Unpublished -> return RecoveryAuditOutcome.WorkspaceDrift
                is PublishedWorkspaceGenerationState.Published -> current.manifest
            }
            if (published != expectedPublished) return RecoveryAuditOutcome.WorkspaceDrift
            requireStableGitWorktreeTransition()
            refreshWorkspace(setOf(WorkspaceSignal.RecoveryProbe))
            requireStableGitWorktreeTransition()
            val currentBuildInputs = resolveBuildSemanticInputIdentity()
            if (currentBuildInputs != modelBuildSemanticIdentity) {
                RecoveryAuditOutcome.BuildSemanticDrift
            } else {
                val auditConfig = try {
                    loadLiveConfig(lastValidConfig)
                } catch (failure: Exception) {
                    rethrowRecoveryAuditCancellation(failure)
                    onConfigFallback(failure)
                    lastValidConfig
                }
                val currentIdentity = captureCandidate(auditConfig, currentBuildInputs).identity
                requireStableGitWorktreeTransition()
                if (
                    currentIdentity.value == published.identity.value &&
                    workspaceGenerationPublication.current() == PublishedWorkspaceGenerationState.Published(published)
                ) {
                    RecoveryAuditOutcome.Current
                } else {
                    RecoveryAuditOutcome.WorkspaceDrift
                }
            }
        } catch (failure: Throwable) {
            rethrowRecoveryAuditCancellation(failure)
            RecoveryAuditOutcome.WorkspaceDrift
        }
    }

    private fun requestRecoveryTransition(outcome: RecoveryAuditOutcome.Drift) {
        semanticAdmission.dirty(outcome.dirtyReason)
        coordinator.observe(outcome.signal)
    }

    private fun rethrowRecoveryAuditCancellation(failure: Throwable) {
        when (failure) {
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw failure
            }

            is CancellationException,
            is ProcessCanceledException,
            -> throw failure
        }
    }

    /**
     * Proof transition: `Duration -> WorkspaceWorkerWaitOutcome`.
     *
     * Maps the injected test waiter or production wakeup capability into one
     * closed worker-lifecycle state. The legacy Boolean test seam and raw
     * millisecond timeout are consumed only inside this effect boundary.
     */
    private fun awaitWork(delay: Duration): WorkspaceWorkerWaitOutcome {
        val delayMillis = delay.toMillis()
        waitForNextPass?.let { wait ->
            if (!wait(delayMillis)) return WorkspaceWorkerWaitOutcome.Interrupted
            if (coordinator.snapshot().pendingSignals.isEmpty()) requestRecoveryAudit()
            return WorkspaceWorkerWaitOutcome.Continue
        }
        return when (eventWakeup.awaitWakeup(delayMillis)) {
            WorkspaceWakeup.Signal -> WorkspaceWorkerWaitOutcome.Continue
            WorkspaceWakeup.RecoveryAudit -> {
                if (coordinator.snapshot().pendingSignals.isEmpty()) requestRecoveryAudit()
                WorkspaceWorkerWaitOutcome.Continue
            }

            WorkspaceWakeup.Interrupted -> WorkspaceWorkerWaitOutcome.Interrupted
        }
    }

    private fun requireActive() {
        if (isCancelled() || Thread.currentThread().isInterrupted) throw ProcessCanceledException()
    }

    private fun requireStableGitWorktreeTransition() {
        when (val transition = gitWorktreeTransitionGuard.inspect()) {
            GitWorktreeTransitionStatus.Stable,
            is GitWorktreeTransitionStatus.MissingLinkedWorktreeGitDirectory,
            -> Unit
            is GitWorktreeTransitionStatus.InProgress ->
                throw GitWorktreeTransitionInProgressException(transition)
            is GitWorktreeTransitionStatus.Unavailable ->
                throw GitWorktreeTransitionInspectionException(transition)
        }
    }

    private fun currentImportedBuildInputs(): BuildSemanticInputIdentity {
        val current = resolveBuildSemanticInputIdentity()
        if (current != modelBuildSemanticIdentity) {
            throw BuildSemanticModelStaleException(
                imported = modelBuildSemanticIdentity,
                current = current,
            )
        }
        return current
    }
}

private enum class WorkspaceWorkerWaitOutcome {
    Continue,
    Interrupted,
}

internal class BuildSemanticInputsMovedDuringRefreshException(
    val before: BuildSemanticInputIdentity,
    val after: BuildSemanticInputIdentity,
) : IllegalStateException("Build-semantic inputs moved during Gradle refresh")

internal class BuildSemanticModelStaleException(
    val imported: BuildSemanticInputIdentity,
    val current: BuildSemanticInputIdentity,
) : IllegalStateException("Build-semantic inputs do not match the imported Gradle model")

private sealed interface RecoveryAuditOutcome {
    data object Current : RecoveryAuditOutcome

    sealed interface Drift : RecoveryAuditOutcome {
        val signal: WorkspaceSignal
        val dirtyReason: String
    }

    data object WorkspaceDrift : Drift {
        override val signal: WorkspaceSignal = WorkspaceSignal.RecoveryAudit
        override val dirtyReason: String = "workspace recovery audit requires reconciliation"
    }

    data object BuildSemanticDrift : Drift {
        override val signal: WorkspaceSignal = WorkspaceSignal.BuildSemantic
        override val dirtyReason: String = "workspace recovery audit found build-semantic drift"
    }
}
private const val EVENT_QUIESCENCE_MILLIS = 250L
private val GIT_TRANSITION_RETRY_DELAY: Duration = Duration.ofMillis(250)
