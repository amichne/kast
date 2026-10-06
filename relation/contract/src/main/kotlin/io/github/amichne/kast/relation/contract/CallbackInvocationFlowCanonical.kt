package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

fun CallbackInvocationFlowRead.canonicalProjection(): String =
    when (this) {
        is CallbackInvocationFlowRead.Unavailable -> "UNAVAILABLE:${cause.name}"
        is CallbackInvocationFlowRead.ContractRejected -> "CONTRACT_REJECTED:${cause.name}"
        is CallbackInvocationFlowRead.Observed -> "OBSERVED:${flow.canonicalProjection()}"
    }

internal fun CallbackInvocationFlow.canonicalProjection(): String = buildString {
    field(basis.workspaceRoot.value)
    field(basis.revisionKey.value)
    body(body)
    binding(binding)
    field(scan.name)
    field(invocations.size.toString())
    invocations.forEach { invocation(it) }
    field(ownerBindings.size.toString())
    ownerBindings.forEach { ownerBinding(it) }
    obligations(obligations)
}

internal fun CallbackBodyBinding.canonicalProjection(): String = buildString { ownerBinding(this@canonicalProjection) }

internal fun CallbackParameterInvocation.canonicalProjection(): String = buildString {
    invocation(this@canonicalProjection)
}

private fun StringBuilder.invocation(invocation: CallbackParameterInvocation) {
    field(invocation.forwardings.size.toString())
    invocation.forwardings.forEach {
        endpoint(it.source.callable)
        field(it.source.position.value.toString())
        occurrence(it.source.parameter)
        occurrence(it.argument)
        binding(CallbackBindingEvidence.Bound(it.target))
    }
    occurrence(invocation.occurrence)
    body(invocation.owner)
    field(invocation.callableTransfers.size.toString())
    invocation.callableTransfers.forEach { transfer ->
        field(transfer.kind.name)
        site(transfer.source)
        site(transfer.target)
    }
}

private fun StringBuilder.ownerBinding(owner: CallbackBodyBinding) {
    body(owner.body)
    when (val supply = owner.supply) {
        is CallbackBodySupply.Invocation -> {
            field("INVOCATION")
            occurrence(supply.occurrence)
        }
        is CallbackBodySupply.DefaultParameter -> {
            field("DEFAULT_PARAMETER")
            occurrence(supply.parameter)
        }
        is CallbackBodySupply.DirectInvocation -> {
            field("DIRECT_INVOCATION")
            occurrence(supply.occurrence)
        }
        CallbackBodySupply.Stored -> field("STORED")
        is CallbackBodySupply.Returned -> {
            field("RETURNED")
            occurrence(supply.occurrence)
        }
        CallbackBodySupply.Unsupported -> field("UNSUPPORTED")
    }
    binding(owner.binding)
    obligations(owner.obligations)
}

private fun StringBuilder.binding(evidence: CallbackBindingEvidence) {
    when (evidence) {
        is CallbackBindingEvidence.Bound -> {
            field("BOUND")
            endpoint(evidence.binding.invocation.enclosing)
            range(evidence.binding.invocation.range)
            body(evidence.binding.invocationOwner)
            endpoint(evidence.binding.invocation.callable)
            field(evidence.binding.position.value.toString())
            occurrence(evidence.binding.parameter)
        }
        is CallbackBindingEvidence.Default -> {
            field("DEFAULT")
            endpoint(evidence.binding.parameter.callable)
            field(evidence.binding.parameter.position.value.toString())
            occurrence(evidence.binding.parameter.parameter)
            occurrence(evidence.binding.defaultValue)
        }
        is CallbackBindingEvidence.Direct -> {
            field("DIRECT")
            field(evidence.binding.basis.workspaceRoot.value)
            field(evidence.binding.basis.revisionKey.value)
            occurrence(evidence.binding.occurrence)
            body(evidence.binding.owner)
        }
        is CallbackBindingEvidence.Unavailable -> {
            field("UNAVAILABLE")
            field(evidence.cause.name)
        }
    }
}

private fun StringBuilder.obligations(obligations: Set<CallbackInvocationFlowCause>) {
    field(obligations.size.toString())
    obligations.sortedBy { it.ordinal }.forEach { field(it.name) }
}

private fun StringBuilder.site(site: ValueSite) {
    endpoint(site.enclosing)
    range(site.range)
    when (val role = site.role) {
        ValueRole.ExpressionResult -> field("EXPRESSION_RESULT")
        ValueRole.LocalBinding -> field("LOCAL_BINDING")
        ValueRole.LocalRead -> field("LOCAL_READ")
        ValueRole.Return -> field("RETURN")
        ValueRole.PropertyAssignment -> field("PROPERTY_ASSIGNMENT")
        is ValueRole.Argument -> {
            field("ARGUMENT")
            endpoint(role.call.enclosing)
            endpoint(role.call.callable)
            range(role.call.range)
            field(role.position.value.toString())
        }
    }
}

private fun StringBuilder.body(body: RelationCallableBody) {
    field(body.file.stableValue)
    range(body.range)
    field(body.compilerIdentity.value)
    when (body) {
        is RelationCallableBody.Named -> {
            field("NAMED")
            field(body.evidence.name.value)
            field(body.evidence.signature.canonicalEncoding().value)
        }
        is RelationCallableBody.Anonymous -> {
            field("ANONYMOUS")
            field(body.signature.canonicalEncoding().value)
        }
    }
}

private fun StringBuilder.endpoint(endpoint: RelationEndpoint) {
    field(endpoint.fingerprint.value)
    field(endpoint.file.stableValue)
    range(endpoint.range)
    field(endpoint.compilerIdentity.value)
    field(endpoint.signature.canonicalEncoding().value)
}

private fun StringBuilder.occurrence(occurrence: RelationOccurrence) {
    field(occurrence.file.stableValue)
    range(occurrence.range)
}

private fun StringBuilder.range(range: ExactDeclarationTextRange) {
    field(range.startInclusive.toString())
    field(range.endExclusive.toString())
}

private fun StringBuilder.field(value: String) {
    append(value.toByteArray(Charsets.UTF_8).size)
    append(':')
    append(value)
}
