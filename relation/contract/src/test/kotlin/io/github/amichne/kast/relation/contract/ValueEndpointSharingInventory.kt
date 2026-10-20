package io.github.amichne.kast.relation.contract

import java.util.IdentityHashMap

/** This inventory covers only the declared direct-call fixture, not arbitrary callback or model graphs. */
internal class ValueEndpointSharingInventory(step: ValueFlowStep) {
    private val sites = IdentityHashMap<ValueSite, Unit>()
    private val frames = IdentityHashMap<ValueCallFrame, Unit>()
    private val endpoints = IdentityHashMap<RelationEndpoint, Unit>()
    val references = mutableListOf<RelationEndpoint>()
    val physicalEndpoints: List<RelationEndpoint>
        get() = endpoints.keys.toList()

    val siteCount: Int
        get() = sites.size

    val frameCount: Int
        get() = frames.size

    init {
        require(step.applications.isEmpty())
        endpoint(step.domain.subject)
        site(step.source)
        step.transfers.forEach { transfer ->
            site(transfer.source)
            site(transfer.target)
            val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.Enter
            frame(operation.frame)
            position(operation.position)
        }
        step.obligations.forEach { site(it.site) }
    }

    private fun endpoint(endpoint: RelationEndpoint) {
        references += endpoint
        endpoints[endpoint] = Unit
        require(endpoint.valueExecutionOwner.enclosing === endpoint)
        require(endpoint.valueExecutionOwner.body == ValueExecutionBody.Declaration)
    }

    private fun owner(owner: ValueExecutionOwner) {
        require(owner.body == ValueExecutionBody.Declaration)
        endpoint(owner.enclosing)
    }

    private fun invocation(call: ValueInvocation) {
        endpoint(call.enclosing)
        endpoint(call.callable)
        owner(call.executionOwner)
    }

    private fun frame(frame: ValueCallFrame) {
        if (frames.put(frame, Unit) != null) return
        require(frame.target == ValueCallTarget.Direct)
        invocation(frame.invocation as ValueInvocation)
        owner(frame.callee)
    }

    private fun position(position: ValueFormalPosition) {
        val parameter = (position as ValueFormalPosition.Parameter).parameter
        endpoint(parameter.callable)
        owner(parameter.executionOwner)
    }

    private fun site(site: ValueSite) {
        if (sites.put(site, Unit) != null) return
        endpoint(site.enclosing)
        owner(site.executionOwner)
        when (val role = site.role) {
            is ValueRole.Argument -> invocation(role.call)
            is ValueRole.Formal -> position(role.position)
            ValueRole.LocalRead -> Unit
            else -> error("This fixture inventory needs an explicit adapter for $role")
        }
        site.valuePath.segments.filterIsInstance<ValuePathSegment.Field>().forEach { endpoint(it.field.declaration) }
        site.callStack.frames.forEach(::frame)
        site.predicates.entries.forEach { predicate ->
            site(predicate.subject.occurrence)
            if (predicate is ValuePredicate.Subtype) endpoint(predicate.resolvedType)
        }
    }
}
