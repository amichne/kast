package io.github.amichne.kast.protocol.contract

internal fun QueryCallbackParameterIdentityWireDocument.sameCallbackProof(
    other: QueryCallbackParameterIdentityWireDocument
): Boolean =
    callable.sameCallbackProof(other.callable) &&
        position == other.position &&
        parameter.sameCallbackProof(other.parameter)

internal fun QueryCallbackBindingWireDocument.sameCallbackProof(other: QueryCallbackBindingWireDocument): Boolean =
    when (this) {
        is QueryCallbackBindingWireDocument.Bound ->
            other is QueryCallbackBindingWireDocument.Bound && sameCallbackProof(other)
        is QueryCallbackBindingWireDocument.Default ->
            other is QueryCallbackBindingWireDocument.Default && sameCallbackProof(other)
        is QueryCallbackBindingWireDocument.Direct ->
            other is QueryCallbackBindingWireDocument.Direct && sameCallbackProof(other)
        is QueryCallbackBindingWireDocument.DependencyContract ->
            other is QueryCallbackBindingWireDocument.DependencyContract && sameCallbackProof(other)
        is QueryCallbackBindingWireDocument.DeferredDependency ->
            other is QueryCallbackBindingWireDocument.DeferredDependency && sameCallbackProof(other)
        is QueryCallbackBindingWireDocument.Unavailable ->
            other is QueryCallbackBindingWireDocument.Unavailable && this == other
    }

internal fun QueryCallbackBindingWireDocument.Bound.sameCallbackProof(
    other: QueryCallbackBindingWireDocument.Bound
): Boolean =
    invocation == other.invocation &&
        invocationOccurrence.sameCallbackProof(other.invocationOccurrence) &&
        invocationOwner.sameCallbackProof(other.invocationOwner) &&
        callable.sameCallbackProof(other.callable) &&
        position == other.position &&
        parameter.sameCallbackProof(other.parameter)

internal fun QueryCallbackBindingWireDocument.Default.sameCallbackProof(
    other: QueryCallbackBindingWireDocument.Default
): Boolean = parameter.sameCallbackProof(other.parameter) && defaultValue.sameCallbackProof(other.defaultValue)

internal fun QueryCallbackBindingWireDocument.Direct.sameCallbackProof(
    other: QueryCallbackBindingWireDocument.Direct
): Boolean =
    basis == other.basis && occurrence.sameCallbackProof(other.occurrence) && owner.sameCallbackProof(other.owner)

internal fun QueryCallbackBindingWireDocument.DependencyContract.sameCallbackProof(
    other: QueryCallbackBindingWireDocument.DependencyContract
): Boolean =
    basis == other.basis &&
        occurrence.sameCallbackProof(other.occurrence) &&
        owner.sameCallbackProof(other.owner) &&
        target == other.target &&
        position == other.position &&
        classDigest == other.classDigest &&
        provenance == other.provenance &&
        invocationKind == other.invocationKind

internal fun QueryCallbackBindingWireDocument.DeferredDependency.sameCallbackProof(
    other: QueryCallbackBindingWireDocument.DeferredDependency
): Boolean =
    basis == other.basis &&
        occurrence.sameCallbackProof(other.occurrence) &&
        owner.sameCallbackProof(other.owner) &&
        target == other.target &&
        position == other.position &&
        binary == other.binary &&
        execution.sameCallbackProof(other.execution)

internal fun QueryCallbackForwardingWireDocument.sameCallbackProof(
    other: QueryCallbackForwardingWireDocument
): Boolean =
    source.sameCallbackProof(other.source) &&
        argument.sameCallbackProof(other.argument) &&
        target.sameCallbackProof(other.target) &&
        callableTransfers.sameProofList(other.callableTransfers) { first, second -> first.sameCallbackProof(second) }

internal fun QueryCallbackSupplierSelectionWireDocument.sameCallbackProof(
    other: QueryCallbackSupplierSelectionWireDocument
): Boolean =
    when (this) {
        is QueryCallbackSupplierSelectionWireDocument.Explicit ->
            other is QueryCallbackSupplierSelectionWireDocument.Explicit && sameCallbackProof(other)
        is QueryCallbackSupplierSelectionWireDocument.Default ->
            other is QueryCallbackSupplierSelectionWireDocument.Default && sameCallbackProof(other)
    }

internal fun QueryCallbackSupplierSelectionWireDocument.Explicit.sameCallbackProof(
    other: QueryCallbackSupplierSelectionWireDocument.Explicit
): Boolean = argument.sameCallbackProof(other.argument)

internal fun QueryCallbackSupplierSelectionWireDocument.Default.sameCallbackProof(
    other: QueryCallbackSupplierSelectionWireDocument.Default
): Boolean = declaration.sameCallbackProof(other.declaration)

internal fun QueryCallbackParameterSupplierWireDocument.sameCallbackProof(
    other: QueryCallbackParameterSupplierWireDocument
): Boolean =
    binding.sameCallbackProof(other.binding) &&
        selection.sameCallbackProof(other.selection) &&
        value.sameCallbackProof(other.value)

internal fun QueryCallbackForwardingEvidenceWireDocument.sameCallbackProof(
    other: QueryCallbackForwardingEvidenceWireDocument
): Boolean =
    when (this) {
        QueryCallbackForwardingEvidenceWireDocument.InvocationRoutes ->
            other == QueryCallbackForwardingEvidenceWireDocument.InvocationRoutes
        is QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph ->
            other is QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph && sameCallbackProof(other)
    }

internal fun QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph.sameCallbackProof(
    other: QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph
): Boolean =
    root.sameCallbackProof(other.root) &&
        formals.sameProofList(other.formals) { first, second -> first.sameCallbackProof(second) } &&
        forwardings.sameProofList(other.forwardings) { first, second -> first.sameCallbackProof(second) }

internal fun QueryCallbackReferenceReceiverWireDocument.sameCallbackProof(
    other: QueryCallbackReferenceReceiverWireDocument
): Boolean =
    when (this) {
        QueryCallbackReferenceReceiverWireDocument.Absent -> other == QueryCallbackReferenceReceiverWireDocument.Absent
        QueryCallbackReferenceReceiverWireDocument.Unbound ->
            other == QueryCallbackReferenceReceiverWireDocument.Unbound
        is QueryCallbackReferenceReceiverWireDocument.Bound ->
            other is QueryCallbackReferenceReceiverWireDocument.Bound && sameCallbackProof(other)
        is QueryCallbackReferenceReceiverWireDocument.Implicit ->
            other is QueryCallbackReferenceReceiverWireDocument.Implicit && sameCallbackProof(other)
    }

internal fun QueryCallbackReferenceReceiverWireDocument.Bound.sameCallbackProof(
    other: QueryCallbackReferenceReceiverWireDocument.Bound
): Boolean = occurrence.sameCallbackProof(other.occurrence)

internal fun QueryCallbackReferenceReceiverWireDocument.Implicit.sameCallbackProof(
    other: QueryCallbackReferenceReceiverWireDocument.Implicit
): Boolean = declaration.sameCallbackProof(other.declaration)

internal fun QueryCallbackCallableWireDocument.sameCallbackProof(other: QueryCallbackCallableWireDocument): Boolean =
    declaration.sameCallbackProof(other.declaration) && compilerTarget == other.compilerTarget

internal fun QueryCallbackBodyWireDocument.sameCallbackProof(other: QueryCallbackBodyWireDocument): Boolean =
    when (this) {
        is QueryCallbackBodyWireDocument.Named ->
            other is QueryCallbackBodyWireDocument.Named && sameCallbackProof(other)
        is QueryCallbackBodyWireDocument.Anonymous ->
            other is QueryCallbackBodyWireDocument.Anonymous && sameCallbackProof(other)
    }

internal fun QueryCallbackBodyWireDocument.Named.sameCallbackProof(
    other: QueryCallbackBodyWireDocument.Named
): Boolean = callable.sameCallbackProof(other.callable)

internal fun QueryCallbackBodyWireDocument.Anonymous.sameCallbackProof(
    other: QueryCallbackBodyWireDocument.Anonymous
): Boolean = occurrence.sameCallbackProof(other.occurrence) && compilerEvidence == other.compilerEvidence

internal fun QueryCallbackInvocationWireDocument.sameCallbackProof(
    other: QueryCallbackInvocationWireDocument
): Boolean =
    occurrence.sameCallbackProof(other.occurrence) &&
        owner.sameCallbackProof(other.owner) &&
        callableTransfers.sameProofList(other.callableTransfers) { first, second -> first.sameCallbackProof(second) } &&
        forwardings.sameProofList(other.forwardings) { first, second -> first.sameCallbackProof(second) }

internal fun QueryCallbackFlowWireDocument.sameCallbackProof(other: QueryCallbackFlowWireDocument): Boolean =
    when (this) {
        is QueryCallbackFlowWireDocument.Immutable ->
            other is QueryCallbackFlowWireDocument.Immutable && sameCallbackProof(other)
        is QueryCallbackFlowWireDocument.Observed ->
            other is QueryCallbackFlowWireDocument.Observed && sameCallbackProof(other)
        is QueryCallbackFlowWireDocument.Unavailable ->
            other is QueryCallbackFlowWireDocument.Unavailable && this == other
        is QueryCallbackFlowWireDocument.ContractRejected ->
            other is QueryCallbackFlowWireDocument.ContractRejected && this == other
    }

internal fun QueryCallbackFlowWireDocument.Immutable.sameCallbackProof(
    other: QueryCallbackFlowWireDocument.Immutable
): Boolean = flow.sameCallbackProof(other.flow)

internal fun QueryCallbackFlowWireDocument.Observed.sameCallbackProof(
    other: QueryCallbackFlowWireDocument.Observed
): Boolean =
    basis == other.basis &&
        body.sameCallbackProof(other.body) &&
        binding.sameCallbackProof(other.binding) &&
        invocations.sameProofList(other.invocations) { first, second -> first.sameCallbackProof(second) } &&
        obligations == other.obligations &&
        ownerBindings.sameProofList(other.ownerBindings) { first, second -> first.sameCallbackProof(second) } &&
        scan == other.scan &&
        forwarding.sameCallbackProof(other.forwarding)

internal fun QueryCallbackFlowWireDocument.Observed.sameCallbackQuestion(
    other: QueryCallbackFlowWireDocument.Observed
): Boolean =
    binding is QueryCallbackBindingWireDocument.Bound &&
        other.binding is QueryCallbackBindingWireDocument.Bound &&
        basis == other.basis &&
        body.sameCallbackProof(other.body) &&
        binding.sameCallbackProof(other.binding)
