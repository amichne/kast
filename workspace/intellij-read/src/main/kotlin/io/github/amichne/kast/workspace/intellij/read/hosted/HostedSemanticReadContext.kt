package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.*
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission

internal class HostedReadFreshness(
    val beforeWrite: () -> Refinement<Unit, HostedQueryFailure>,
    val current: suspend () -> Refinement<Unit, HostedQueryFailure>,
)

/** Request-local detached scope and original-owner freshness checks; contains no native objects. */
class HostedSemanticReadContext
internal constructor(
    val authority: LiveSemanticReadAuthority,
    val model: WorkspaceSearchScopeModel,
    val sourceFiles: IntellijSemanticSourceFileAdmission,
    val observation: io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation =
        io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation.None,
    val limits: io.github.amichne.kast.kernel.ReadLimits = io.github.amichne.kast.kernel.ReadLimits.Default,
    val timeAllowance: HostedSemanticTimeAllowance,
    private val freshness: HostedReadFreshness,
    private val publicationEffects: HostedReadPublicationOwner = HostedReadPublicationOwner(),
    val readTrace: HostedReadTraceObservation = HostedReadTraceObservation.Unobserved,
) {
    val executionBudget
        get() = timeAllowance.executionBudget

    private enum class Lifetime {
        ACTIVE,
        ENDED,
    }

    @Volatile private var lifetime = Lifetime.ACTIVE

    val validation = SemanticReadValidationPort { expected ->
        when {
            expected.workspaceRoot != authority.workspaceRoot -> SemanticReadValidation.ROOT_MISMATCH
            expected !== authority -> SemanticReadValidation.MOVED
            else ->
                when (validate()) {
                    is Refinement.Refined -> SemanticReadValidation.CURRENT
                    is Refinement.Rejected -> SemanticReadValidation.MOVED
                }
        }
    }

    /** Captures a narrower one-use probe; the read context still expires normally at request completion. */
    fun prepareWriteObservation(
        expected: LiveSemanticReadReference,
        expectedModel: WorkspaceSearchScopeModel,
    ): Refinement<HostedPreWriteObservation, HostedQueryFailure> =
        when {
            lifetime != Lifetime.ACTIVE -> Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
            expected != authority.reference -> Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
            expectedModel.workspaceRoot != model.workspaceRoot || expectedModel.sourceRoots != model.sourceRoots ->
                Refinement.Rejected(HostedQueryFailure.MODEL_MOVED)
            else -> Refinement.Refined(HostedPreWriteObservation.capture(authority.reference, freshness.beforeWrite))
        }

    fun preparePublication(effect: HostedReadPublicationEffect): Refinement<Unit, HostedQueryFailure> =
        if (lifetime == Lifetime.ACTIVE) publicationEffects.prepare(effect)
        else Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)

    internal suspend fun validate(): Refinement<Unit, HostedQueryFailure> =
        when (lifetime) {
            Lifetime.ACTIVE -> freshness.current()
            Lifetime.ENDED -> Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
        }

    internal fun end() {
        lifetime = Lifetime.ENDED
    }
}

sealed interface HostedSemanticReadResult<out Value> {
    data class Completed<Value>(val value: Value) : HostedSemanticReadResult<Value>

    data class Rejected(
        val failure: HostedQueryFailure,
        val stage: HostedQueryStage,
        val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence =
            io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence.Absent,
    ) : HostedSemanticReadResult<Nothing>
}

/** One root and nonce for the service lifetime; retirement cannot rebind a project. */
internal class HostedLiveReadAuthoritySession(private val host: IdeReadHostLifetime) {
    private sealed interface State {
        data object Unbound : State

        data class Bound(val root: CanonicalWorkspaceRoot, val owner: LiveSemanticReadOwner) : State

        data object Retired : State
    }

    private var state: State = State.Unbound

    @Synchronized
    fun admit(
        root: CanonicalWorkspaceRoot,
        observe: () -> VfsPassiveReadAdmission,
    ): Refinement<LiveSemanticReadAuthority, HostedQueryFailure> {
        val bound =
            when (val current = state) {
                State.Unbound -> State.Bound(root, LiveSemanticReadOwner(root, host)).also { state = it }
                is State.Bound -> current
                State.Retired -> return Refinement.Rejected(HostedQueryFailure.RETIRED)
            }
        return when (val result = bound.owner.admit(observe)) {
            is Refinement.Refined -> result
            is Refinement.Rejected ->
                Refinement.Rejected(
                    when (val failure = result.failure) {
                        is LiveSemanticReadAdmissionFailure.Freshness -> HostedQueryFailure.Freshness(failure.cause)
                        is LiveSemanticReadAdmissionFailure.Authority -> HostedQueryFailure.LiveAuthority(failure.cause)
                    }
                )
        }
    }

    @Synchronized
    fun retire() {
        when (val current = state) {
            is State.Bound -> current.owner.retire()
            State.Unbound,
            State.Retired -> Unit
        }
        state = State.Retired
    }
}
