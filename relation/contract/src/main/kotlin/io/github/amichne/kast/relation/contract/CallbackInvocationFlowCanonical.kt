package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

fun CallbackInvocationFlowRead.canonicalProjection(): String =
    when (this) {
        is CallbackInvocationFlowRead.Immutable -> "IMMUTABLE:${flow.canonicalProjection()}"
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
    when (val proof = forwarding) {
        CallbackForwardingEvidence.InvocationRoutes -> field("INVOCATION_ROUTES")
        is CallbackForwardingEvidence.ExhaustedGraph -> {
            field("EXHAUSTED_GRAPH")
            formal(proof.graph.root)
            field(proof.graph.formals.size.toString())
            proof.graph.formals.forEach { formal(it) }
            field(proof.graph.forwardings.size.toString())
            proof.graph.forwardings.forEach { forwarding(it) }
        }
    }
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

internal fun CallbackParameterIdentity.canonicalProjection(): String = buildString {
    formal(this@canonicalProjection)
}

internal fun CallbackParameterForwarding.canonicalProjection(): String = buildString {
    forwarding(this@canonicalProjection)
}

private fun StringBuilder.invocation(invocation: CallbackParameterInvocation) {
    field(invocation.forwardings.size.toString())
    invocation.forwardings.forEach { forwarding(it) }
    occurrence(invocation.occurrence)
    body(invocation.owner)
    field(invocation.callableTransfers.size.toString())
    invocation.callableTransfers.forEach { transfer ->
        field(transfer.kind.name)
        site(transfer.source)
        site(transfer.target)
    }
}

private fun StringBuilder.formal(formal: CallbackParameterIdentity) {
    endpoint(formal.callable)
    field(formal.position.value.toString())
    occurrence(formal.parameter)
}

private fun StringBuilder.forwarding(forwarding: CallbackParameterForwarding) {
    formal(forwarding.source)
    occurrence(forwarding.argument)
    binding(CallbackBindingEvidence.Bound(forwarding.target))
    field(forwarding.callableTransfers.size.toString())
    forwarding.callableTransfers.forEach { transfer ->
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

internal fun ImmutableCallbackValue.canonicalProjection(): String = buildString {
    when (val value = origin) {
        is ImmutableCallbackValueOrigin.Returned -> factory(value.factory)
        is ImmutableCallbackValueOrigin.Anonymous -> body(value.body)
        is ImmutableCallbackValueOrigin.Named -> {
            field("NAMED_REFERENCE")
            occurrence(value.occurrence)
            endpoint(value.target)
            field(value.receivers.dispatch.canonicalProjection())
            field(value.receivers.extension.canonicalProjection())
        }
    }
    site(source)
    site(destination)
    field(transfers.size.toString())
    transfers.forEach {
        field(it.kind.name)
        site(it.source)
        site(it.target)
    }
}

internal fun ImmutableCallbackInvocationFlow.canonicalProjection(): String = buildString {
    field("IMMUTABLE")
    when (val value = origin) {
        is ImmutableCallbackValueOrigin.Returned -> factory(value.factory)
        is ImmutableCallbackValueOrigin.Anonymous -> body(value.body)
        is ImmutableCallbackValueOrigin.Named -> {
            occurrence(value.occurrence)
            endpoint(value.target)
            field(value.receivers.dispatch.canonicalProjection())
            field(value.receivers.extension.canonicalProjection())
        }
    }
    site(source)
    field(scan.name)
    obligations(obligations)
    field(uses.size.toString())
    uses.forEach { immutableUse(it) }
}

internal fun CallbackParameterSupplier.canonicalProjection(): String = buildString {
    binding(CallbackBindingEvidence.Bound(binding))
    when (val selected = selection) {
        is CallbackSupplierSelection.Explicit -> {
            field("EXPLICIT")
            site(selected.argument)
        }
        is CallbackSupplierSelection.Default -> {
            field("DEFAULT")
            binding(CallbackBindingEvidence.Default(selected.declaration))
        }
    }
    field(value.canonicalProjection())
}

private fun StringBuilder.factory(value: CallbackFactoryReturn) {
    field("FACTORY_RETURN")
    endpoint(value.invocation.enclosing)
    endpoint(value.invocation.callable)
    field(value.invocation.range.startInclusive.toString())
    field(value.invocation.range.endExclusive.toString())
    field(value.returnedValue.canonicalProjection())
    factoryBody(value.bodyCalls)
    field(value.captures.size.toString())
    value.captures.forEach { capture ->
        binding(CallbackBindingEvidence.Bound(capture.binding))
        when (val selected = capture.selection) {
            is CallbackFactoryCaptureSelection.Explicit -> {
                field("EXPLICIT")
                site(selected.value)
            }
            is CallbackFactoryCaptureSelection.Default -> {
                field("DEFAULT")
                binding(CallbackBindingEvidence.Default(selected.declaration))
            }
        }
        when (val content = capture.content) {
            CallbackFactoryCaptureContent.Scalar -> field("SCALAR")
            is CallbackFactoryCaptureContent.Callable -> {
                field("CALLABLE")
                field(content.values.size.toString())
                content.values.forEach { field(it.canonicalProjection()) }
                field(content.invocations.size.toString())
                content.invocations.forEach { invocation(it) }
            }
        }
    }
}

private fun StringBuilder.factoryBody(proof: CallbackFactoryBodyCalls) {
    when (proof) {
        CallbackFactoryBodyCalls.NotApplicable -> field("NOT_APPLICABLE")
        is CallbackFactoryBodyCalls.Exhaustive -> {
            field("EXHAUSTIVE")
            body(proof.body)
            field(proof.calls.size.toString())
            proof.calls.forEach { call ->
                occurrence(call.occurrence)
                when (call) {
                    is CallbackFactoryBodyCall.Named -> {
                        field("NAMED")
                        endpoint(call.target)
                    }
                    is CallbackFactoryBodyCall.Captured -> {
                        field("CAPTURED")
                        formal(call.formal)
                        invocation(call.invocation)
                    }
                    is CallbackFactoryBodyCall.Boundary -> {
                        field("BOUNDARY")
                        field(call.callable.signature.canonicalEncoding().value)
                        field(call.callable.kind.name)
                        field(call.callable.compilerIdentity.value)
                        field(call.callable.origin.name)
                        field(call.callable.moduleKind.name)
                        field(call.callable.moduleName.value)
                        field(call.disposition.name)
                    }
                }
            }
        }
    }
}

private fun StringBuilder.immutableUse(use: ImmutableCallbackInvocationUse) {
    field(use.value.canonicalProjection())
    when (use) {
        is ImmutableCallbackInvocationUse.Unused -> field("UNUSED")
        is ImmutableCallbackInvocationUse.Direct -> {
            field("DIRECT")
            binding(CallbackBindingEvidence.Direct(use.binding))
        }
        is ImmutableCallbackInvocationUse.Supplied -> {
            field("SUPPLIED")
            binding(CallbackBindingEvidence.Bound(use.supplier.binding))
            formal(use.summary.formal)
            field(use.summary.invocations.size.toString())
            use.summary.invocations.forEach { invocation(it) }
            when (val selection = use.supplier.selection) {
                is CallbackSupplierSelection.Explicit -> {
                    field("EXPLICIT")
                    site(selection.argument)
                }
                is CallbackSupplierSelection.Default -> {
                    field("DEFAULT")
                    occurrence(selection.declaration.defaultValue)
                }
            }
            when (val graph = use.summary.forwarding) {
                CallbackForwardingEvidence.InvocationRoutes -> field("INVOCATION_ROUTES")
                is CallbackForwardingEvidence.ExhaustedGraph -> {
                    field("EXHAUSTED_GRAPH")
                    formal(graph.graph.root)
                    field(graph.graph.formals.size.toString())
                    graph.graph.formals.forEach { formal(it) }
                    field(graph.graph.forwardings.size.toString())
                    graph.graph.forwardings.forEach { forwarding(it) }
                }
            }
        }
    }
}
