package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

/** Repeated formals remain legal when the callback body or supplying call differs. */
internal class CallbackBodyReads(private val resources: ResourceBudget) {
    private val active = mutableSetOf<CallbackBodyReadIdentity>()

    fun read(
        body: RelationCallableBody.Anonymous,
        binding: CallbackBindingEvidence,
        basis: SemanticReadIdentity,
        operation: () -> CallbackInvocationFlowRead,
    ): CallbackInvocationFlowRead {
        val identity = CallbackBodyReadIdentity(body, binding, basis)
        if (identity in active)
            return CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.CALLBACK_CYCLE)
        when (resources) {
            is ResourceBudget.Bounded ->
                if (active.size >= MAXIMUM_OWNER_DEPTH)
                    return CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.WORK_LIMIT_REACHED)
            ResourceBudget.RunnerControlled -> Unit
        }
        active.add(identity)
        return try {
            operation()
        } finally {
            active.remove(identity)
        }
    }
}

private data class CallbackBodyReadIdentity(
    val body: RelationCallableBody.Anonymous,
    val binding: CallbackBindingEvidence,
    val basis: SemanticReadIdentity,
)

private const val MAXIMUM_OWNER_DEPTH = 64
