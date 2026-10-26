package io.github.amichne.kast.relation.contract

/** One execution shares full proofs. A restored checkpoint seeds its lookup once. */
class ValueFlowProofSharing(previous: Collection<ValueFlowStep> = emptyList()) {
    private val callables = linkedMapOf<RelationEndpoint.Resolved, CallableProof>()
    private val executionOwners = linkedMapOf<Pair<RelationEndpoint, ValueExecutionIdentity>, ValueExecutionOwner>()

    init {
        for (step in previous) {
            remember(step.source)
            step.transfers.forEach { remember(it.target) }
            step.reviewedTransfers.forEach { remember(it.target) }
        }
    }

    fun share(step: ValueFlowStep): ValueFlowStep = step.shareCallableEvidence(this)

    internal class CallableProof(invocation: ValueInvocation) {
        val endpoint = invocation.callable
        val signature = invocation.signature
        val operation = invocation.operation
    }

    internal fun callable(invocation: ValueInvocation): CallableProof =
        when (val endpoint = invocation.callable) {
            is RelationEndpoint.Resolved -> callables.getOrPut(endpoint) { CallableProof(invocation) }
            is RelationEndpoint.Subject -> CallableProof(invocation)
        }

    internal fun executionOwner(owner: ValueExecutionOwner): ValueExecutionOwner =
        executionOwners.getOrPut(owner.enclosing to owner.identity) { owner }

    private fun remember(site: ValueSite) {
        executionOwner(site.executionOwner)
        val invocation =
            when (val role = site.role) {
                is ValueRole.LoopResult -> {
                    role.result.protocol.invocations().forEach {
                        callable(it)
                        executionOwner(it.executionOwner)
                    }
                    return
                }
                is ValueRole.Argument -> role.call
                is ValueRole.Receiver -> role.call
                is ValueRole.Formal,
                ValueRole.ExpressionResult,
                ValueRole.LocalBinding,
                ValueRole.LocalRead,
                ValueRole.Return,
                ValueRole.PropertyAssignment -> return
            }
        callable(invocation)
        executionOwner(invocation.executionOwner)
    }
}

/** Input sites stay local to one transformation. Only their full proofs survive across reads. */
internal class ValueFlowSiteSharing(private val proofs: ValueFlowProofSharing) {
    private val sites = java.util.IdentityHashMap<ValueSite, ValueSite>()

    fun site(value: ValueSite): ValueSite = sites.getOrPut(value) { value.shareCallableEvidence(proofs) }
}
