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
import io.github.amichne.kast.idea.transition.PreparedWorkspacePublication
import io.github.amichne.kast.idea.transition.TransitionRun
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceTransitionCoordinator
import io.github.amichne.kast.idea.transition.WorkspaceTransitionOperations
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.idea.transition.WorkspaceWakeup
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import java.util.concurrent.CancellationException

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
    private val onCompleted: (KastSourceIndexSummary) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    onTransition: (WorkspaceTransitionSnapshot) -> Unit,
) {
    private var lastValidConfig = initialConfig
    private var cycleConfig = initialConfig
    private var cycleCandidate: WorkspaceReconciliationCandidate? = null
    private var cycleResult: IndexingPassResult? = null
    private var reconciliationToken: IdeaIndexSemanticAdmission.ReconciliationToken? = null
    private var publishedSummary: KastSourceIndexSummary? = null
    private var consecutiveFailures = 0
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
                cycleResult?.graphFailure?.let { throw it }
                requireActive()
                return reconciledIdentity
            }

            override fun preparePublication(identity: WorkspaceStateIdentity): PreparedWorkspacePublication {
                requireActive()
                return workspaceGenerationPublication.prepare(identity)
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
                        publishedSummary = result.summary
                        GenerationPublication.Published(publication.commit)
                    }

                    IdeaIndexSemanticAdmission.ReadyPublication.InvalidatedBeforeCommit ->
                        GenerationPublication.InvalidatedBeforeCommit

                    is IdeaIndexSemanticAdmission.ReadyPublication.InvalidatedAfterCommit ->
                        GenerationPublication.InvalidatedAfterCommit(publication.commit)
                }
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

    fun observe(signal: WorkspaceSignal) {
        coordinator.observe(signal)
    }

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
                    if (!awaitWork(RECOVERY_AUDIT_MILLIS)) return
                }

                TransitionRun.Published -> {
                    consecutiveFailures = 0
                    onCompleted(checkNotNull(publishedSummary))
                    publishedSummary = null
                    if (!awaitWork(RECOVERY_AUDIT_MILLIS)) return
                }

                TransitionRun.Invalidated -> Unit

                TransitionRun.Retry -> {
                    if (!awaitWork(GIT_TRANSITION_RETRY_MILLIS)) return
                }

                TransitionRun.Blocked -> {
                    consecutiveFailures += 1
                    if (!awaitWork(indexingRetryDelayMillis(consecutiveFailures))) return
                }
            }
        }
    }

    private fun recoveryAuditOutcome(
        expectedPublished: PublishedWorkspaceGenerationManifest,
    ): RecoveryAuditOutcome {
        return try {
            requireActive()
            val published = workspaceGenerationPublication.current()
                ?: return RecoveryAuditOutcome.WorkspaceDrift
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
                    workspaceGenerationPublication.current() == published
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

internal class BuildSemanticInputsMovedDuringRefreshException(
    val before: BuildSemanticInputIdentity,
    val after: BuildSemanticInputIdentity,
) : IllegalStateException("Build-semantic inputs moved during Gradle refresh")

internal class BuildSemanticModelStaleException(
    val imported: BuildSemanticInputIdentity,
    val current: BuildSemanticInputIdentity,
) : IllegalStateException("Build-semantic inputs do not match the imported Gradle model")

internal data class WorkspaceReconciliationCandidate(
    val identity: WorkspaceStateIdentity,
    val indexingCandidate: WorkspaceIndexingCandidate?,
)

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
private const val GIT_TRANSITION_RETRY_MILLIS = 250L
