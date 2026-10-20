package io.github.amichne.kast.protocol.contract

internal fun QueryImmutableCallbackUseWireDocument.sameCallbackProof(
    other: QueryImmutableCallbackUseWireDocument
): Boolean =
    when (this) {
        is QueryImmutableCallbackUseWireDocument.Supplied ->
            other is QueryImmutableCallbackUseWireDocument.Supplied && sameCallbackProof(other)
        is QueryImmutableCallbackUseWireDocument.Direct ->
            other is QueryImmutableCallbackUseWireDocument.Direct && sameCallbackProof(other)
        is QueryImmutableCallbackUseWireDocument.Unused ->
            other is QueryImmutableCallbackUseWireDocument.Unused && sameCallbackProof(other)
    }

internal fun QueryImmutableCallbackUseWireDocument.Supplied.sameCallbackProof(
    other: QueryImmutableCallbackUseWireDocument.Supplied
): Boolean =
    supplier.sameCallbackProof(other.supplier) &&
        invocations.sameProofList(other.invocations) { first, second -> first.sameCallbackProof(second) } &&
        forwarding.sameCallbackProof(other.forwarding) &&
        summaryOwnerBindings.sameProofList(other.summaryOwnerBindings) { first, second ->
            first.sameCallbackProof(second)
        } &&
        summaryObligations == other.summaryObligations

internal fun QueryImmutableCallbackUseWireDocument.Direct.sameCallbackProof(
    other: QueryImmutableCallbackUseWireDocument.Direct
): Boolean = value.sameCallbackProof(other.value) && binding.sameCallbackProof(other.binding)

internal fun QueryImmutableCallbackUseWireDocument.Unused.sameCallbackProof(
    other: QueryImmutableCallbackUseWireDocument.Unused
): Boolean = value.sameCallbackProof(other.value)

internal fun QueryImmutableCallbackFlowWireDocument.sameCallbackProof(
    other: QueryImmutableCallbackFlowWireDocument
): Boolean =
    sourceValue.sameCallbackProof(other.sourceValue) &&
        uses.sameProofList(other.uses) { first, second -> first.sameCallbackProof(second) } &&
        obligations == other.obligations &&
        scan == other.scan &&
        ownerBindings.sameProofList(other.ownerBindings) { first, second -> first.sameCallbackProof(second) }

internal fun QueryCallbackFactoryBodyCallsWireDocument.sameCallbackProof(
    other: QueryCallbackFactoryBodyCallsWireDocument
): Boolean =
    when (this) {
        QueryCallbackFactoryBodyCallsWireDocument.NotApplicable ->
            other == QueryCallbackFactoryBodyCallsWireDocument.NotApplicable
        is QueryCallbackFactoryBodyCallsWireDocument.Exhaustive ->
            other is QueryCallbackFactoryBodyCallsWireDocument.Exhaustive && sameCallbackProof(other)
    }

internal fun QueryCallbackFactoryBodyCallsWireDocument.Exhaustive.sameCallbackProof(
    other: QueryCallbackFactoryBodyCallsWireDocument.Exhaustive
): Boolean =
    body.sameCallbackProof(other.body) &&
        calls.sameProofList(other.calls) { first, second -> first.sameCallbackProof(second) }

internal fun QueryCallbackFactoryBodyCallWireDocument.sameCallbackProof(
    other: QueryCallbackFactoryBodyCallWireDocument
): Boolean =
    when (this) {
        is QueryCallbackFactoryBodyCallWireDocument.Named ->
            other is QueryCallbackFactoryBodyCallWireDocument.Named && sameCallbackProof(other)
        is QueryCallbackFactoryBodyCallWireDocument.Captured ->
            other is QueryCallbackFactoryBodyCallWireDocument.Captured && sameCallbackProof(other)
        is QueryCallbackFactoryBodyCallWireDocument.Boundary ->
            other is QueryCallbackFactoryBodyCallWireDocument.Boundary && sameCallbackProof(other)
    }

internal fun QueryCallbackFactoryBodyCallWireDocument.Named.sameCallbackProof(
    other: QueryCallbackFactoryBodyCallWireDocument.Named
): Boolean = occurrence.sameCallbackProof(other.occurrence) && target.sameCallbackProof(other.target)

internal fun QueryCallbackFactoryBodyCallWireDocument.Captured.sameCallbackProof(
    other: QueryCallbackFactoryBodyCallWireDocument.Captured
): Boolean = invocation.sameCallbackProof(other.invocation) && formal.sameCallbackProof(other.formal)

internal fun QueryCallbackFactoryBodyCallWireDocument.Boundary.sameCallbackProof(
    other: QueryCallbackFactoryBodyCallWireDocument.Boundary
): Boolean =
    occurrence.sameCallbackProof(other.occurrence) && callable == other.callable && disposition == other.disposition

internal fun QueryImmutableCallbackValueOriginWireDocument.sameCallbackProof(
    other: QueryImmutableCallbackValueOriginWireDocument
): Boolean =
    when (this) {
        is QueryImmutableCallbackValueOriginWireDocument.Returned ->
            other is QueryImmutableCallbackValueOriginWireDocument.Returned && this == other
        is QueryImmutableCallbackValueOriginWireDocument.Anonymous ->
            other is QueryImmutableCallbackValueOriginWireDocument.Anonymous && sameCallbackProof(other)
        is QueryImmutableCallbackValueOriginWireDocument.Named ->
            other is QueryImmutableCallbackValueOriginWireDocument.Named && sameCallbackProof(other)
    }

internal fun QueryImmutableCallbackValueOriginWireDocument.Anonymous.sameCallbackProof(
    other: QueryImmutableCallbackValueOriginWireDocument.Anonymous
): Boolean = body.sameCallbackProof(other.body)

internal fun QueryImmutableCallbackValueOriginWireDocument.Named.sameCallbackProof(
    other: QueryImmutableCallbackValueOriginWireDocument.Named
): Boolean =
    basis == other.basis &&
        occurrence.sameCallbackProof(other.occurrence) &&
        target.sameCallbackProof(other.target) &&
        dispatchReceiver.sameCallbackProof(other.dispatchReceiver) &&
        extensionReceiver.sameCallbackProof(other.extensionReceiver)

internal fun QueryImmutableCallbackValueWireDocument.sameCallbackProof(
    other: QueryImmutableCallbackValueWireDocument
): Boolean =
    origin.sameCallbackProof(other.origin) &&
        source.sameCallbackProof(other.source) &&
        destination.sameCallbackProof(other.destination) &&
        transfers.sameProofList(other.transfers) { first, second -> first.sameCallbackProof(second) } &&
        invokedCallables.sameProofList(other.invokedCallables) { first, second -> first.sameCallbackProof(second) } &&
        factories.sameProofList(other.factories) { first, second -> first.sameCallbackProof(second) }

internal fun QueryImmutableCallbackValueNodeWireDocument.sameCallbackProof(
    other: QueryImmutableCallbackValueNodeWireDocument
): Boolean =
    origin.sameCallbackProof(other.origin) &&
        source.sameCallbackProof(other.source) &&
        destination.sameCallbackProof(other.destination) &&
        transfers.sameProofList(other.transfers) { first, second -> first.sameCallbackProof(second) } &&
        invokedCallables.sameProofList(other.invokedCallables) { first, second -> first.sameCallbackProof(second) }

internal fun QueryCallbackFactoryWireDocument.sameCallbackProof(other: QueryCallbackFactoryWireDocument): Boolean =
    enclosing == other.enclosing &&
        invocation == other.invocation &&
        callable.sameCallbackProof(other.callable) &&
        returnedValue.sameCallbackProof(other.returnedValue) &&
        captures.sameProofList(other.captures) { first, second -> first.sameCallbackProof(second) } &&
        bodyCalls.sameCallbackProof(other.bodyCalls)

internal fun QueryCallbackFactoryCaptureWireDocument.sameCallbackProof(
    other: QueryCallbackFactoryCaptureWireDocument
): Boolean =
    binding.sameCallbackProof(other.binding) &&
        selection.sameCallbackProof(other.selection) &&
        content.sameCallbackProof(other.content)

internal fun QueryCallbackFactoryCaptureContentWireDocument.sameCallbackProof(
    other: QueryCallbackFactoryCaptureContentWireDocument
): Boolean =
    when (this) {
        QueryCallbackFactoryCaptureContentWireDocument.Scalar ->
            other == QueryCallbackFactoryCaptureContentWireDocument.Scalar
        is QueryCallbackFactoryCaptureContentWireDocument.Callable ->
            other is QueryCallbackFactoryCaptureContentWireDocument.Callable && sameCallbackProof(other)
    }

internal fun QueryCallbackFactoryCaptureContentWireDocument.Callable.sameCallbackProof(
    other: QueryCallbackFactoryCaptureContentWireDocument.Callable
): Boolean =
    values.sameProofList(other.values) { first, second -> first.sameCallbackProof(second) } &&
        invocations.sameProofList(other.invocations) { first, second -> first.sameCallbackProof(second) }
