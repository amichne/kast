package io.github.amichne.kast.relation.contract

internal class ValueFlowProofSharing(previous: Collection<ValueFlowStep>) {
    private val callables = linkedMapOf<RelationEndpoint.Resolved, CallableProof>()
    private val executionOwners = linkedMapOf<Pair<RelationEndpoint, ValueExecutionIdentity>, ValueExecutionOwner>()
    private val sites = java.util.IdentityHashMap<ValueSite, ValueSite>()

    init {
        for (step in previous) {
            remember(step.source)
            step.transfers.forEach { remember(it.target) }
        }
    }

    class CallableProof(invocation: ValueInvocation) {
        val endpoint = invocation.callable
        val signature = invocation.signature
    }

    fun callable(invocation: ValueInvocation): CallableProof =
        when (val endpoint = invocation.callable) {
            is RelationEndpoint.Resolved -> callables.getOrPut(endpoint) { CallableProof(invocation) }
            is RelationEndpoint.Subject -> CallableProof(invocation)
        }

    fun executionOwner(owner: ValueExecutionOwner): ValueExecutionOwner =
        executionOwners.getOrPut(owner.enclosing to owner.identity) { owner }

    fun site(value: ValueSite): ValueSite = sites.getOrPut(value) { value.shareCallableEvidence(this) }

    private fun remember(site: ValueSite) {
        executionOwner(site.executionOwner)
        val invocation =
            when (val role = site.role) {
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
