package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.*
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission

/** Request-local detached scope and original-owner freshness checks; contains no native objects. */
class HostedSemanticReadContext internal constructor(
    val authority: LiveSemanticReadAuthority,
    val model: WorkspaceSearchScopeModel,
    val sourceFiles: IntellijSemanticSourceFileAdmission,
    private val validateCurrent: suspend () -> Refinement<Unit, HostedQueryFailure>,
) {
    private enum class Lifetime { ACTIVE, ENDED }
    @Volatile private var lifetime = Lifetime.ACTIVE

    val validation = SemanticReadValidationPort { expected ->
        when {
            expected.workspaceRoot != authority.workspaceRoot -> SemanticReadValidation.ROOT_MISMATCH
            expected !== authority -> SemanticReadValidation.MOVED
            else -> when (validate()) {
                is Refinement.Refined -> SemanticReadValidation.CURRENT
                is Refinement.Rejected -> SemanticReadValidation.MOVED
            }
        }
    }

    internal suspend fun validate(): Refinement<Unit, HostedQueryFailure> = when (lifetime) {
        Lifetime.ACTIVE -> validateCurrent()
        Lifetime.ENDED -> Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
    }

    internal fun end() { lifetime = Lifetime.ENDED }
}

sealed interface HostedSemanticReadResult<out Value> {
    data class Completed<Value>(val value: Value) : HostedSemanticReadResult<Value>
    data class Rejected(val failure: HostedQueryFailure, val stage: HostedQueryStage) : HostedSemanticReadResult<Nothing>
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
    fun admit(freshness: VfsPassiveReadCapability): Refinement<LiveSemanticReadAuthority, LiveSemanticReadFailure> {
        val bound = when (val current = state) {
            State.Unbound -> State.Bound(freshness.canonicalRoot, LiveSemanticReadOwner(freshness.canonicalRoot, host)).also { state = it }
            is State.Bound -> current
            State.Retired -> return Refinement.Rejected(LiveSemanticReadFailure.RETIRED)
        }
        return bound.owner.admit(freshness)
    }

    @Synchronized
    fun retire() {
        when (val current = state) {
            is State.Bound -> current.owner.retire()
            State.Unbound, State.Retired -> Unit
        }
        state = State.Retired
    }
}
