package io.github.amichne.kast.protocol.contract

internal fun QueryDeferredSupplierExecutionWireDocument.sameCallbackProof(
    other: QueryDeferredSupplierExecutionWireDocument
): Boolean =
    when (this) {
        QueryDeferredSupplierExecutionWireDocument.Declaration ->
            other == QueryDeferredSupplierExecutionWireDocument.Declaration
        is QueryDeferredSupplierExecutionWireDocument.Accessor ->
            other is QueryDeferredSupplierExecutionWireDocument.Accessor && sameCallbackProof(other)
        is QueryDeferredSupplierExecutionWireDocument.Initializer ->
            other is QueryDeferredSupplierExecutionWireDocument.Initializer && sameCallbackProof(other)
        is QueryDeferredSupplierExecutionWireDocument.Invoked ->
            other is QueryDeferredSupplierExecutionWireDocument.Invoked && sameCallbackProof(other)
    }

internal fun QueryDeferredSupplierExecutionWireDocument.Accessor.sameCallbackProof(
    other: QueryDeferredSupplierExecutionWireDocument.Accessor
): Boolean = kind == other.kind && occurrence.sameCallbackProof(other.occurrence)

internal fun QueryDeferredSupplierExecutionWireDocument.Initializer.sameCallbackProof(
    other: QueryDeferredSupplierExecutionWireDocument.Initializer
): Boolean = occurrence.sameCallbackProof(other.occurrence)

internal fun QueryDeferredSupplierExecutionWireDocument.Invoked.sameCallbackProof(
    other: QueryDeferredSupplierExecutionWireDocument.Invoked
): Boolean = steps.sameProofList(other.steps) { first, second -> first.sameCallbackProof(second) }

internal fun QueryDeferredDirectInvocationWireDocument.sameCallbackProof(
    other: QueryDeferredDirectInvocationWireDocument
): Boolean =
    basis == other.basis &&
        occurrence.sameCallbackProof(other.occurrence) &&
        owner.sameCallbackProof(other.owner) &&
        body.sameCallbackProof(other.body)

internal fun QueryCallbackBodySupplyWireDocument.sameCallbackProof(
    other: QueryCallbackBodySupplyWireDocument
): Boolean =
    when (this) {
        is QueryCallbackBodySupplyWireDocument.Invocation ->
            other is QueryCallbackBodySupplyWireDocument.Invocation && sameCallbackProof(other)
        is QueryCallbackBodySupplyWireDocument.DefaultParameter ->
            other is QueryCallbackBodySupplyWireDocument.DefaultParameter && sameCallbackProof(other)
        is QueryCallbackBodySupplyWireDocument.DirectInvocation ->
            other is QueryCallbackBodySupplyWireDocument.DirectInvocation && sameCallbackProof(other)
        is QueryCallbackBodySupplyWireDocument.Returned ->
            other is QueryCallbackBodySupplyWireDocument.Returned && sameCallbackProof(other)
        QueryCallbackBodySupplyWireDocument.Unsupported -> other == QueryCallbackBodySupplyWireDocument.Unsupported
        QueryCallbackBodySupplyWireDocument.Stored -> other == QueryCallbackBodySupplyWireDocument.Stored
    }

internal fun QueryCallbackBodySupplyWireDocument.Invocation.sameCallbackProof(
    other: QueryCallbackBodySupplyWireDocument.Invocation
): Boolean = occurrence.sameCallbackProof(other.occurrence)

internal fun QueryCallbackBodySupplyWireDocument.DefaultParameter.sameCallbackProof(
    other: QueryCallbackBodySupplyWireDocument.DefaultParameter
): Boolean = parameter.sameCallbackProof(other.parameter)

internal fun QueryCallbackBodySupplyWireDocument.DirectInvocation.sameCallbackProof(
    other: QueryCallbackBodySupplyWireDocument.DirectInvocation
): Boolean = occurrence.sameCallbackProof(other.occurrence)

internal fun QueryCallbackBodySupplyWireDocument.Returned.sameCallbackProof(
    other: QueryCallbackBodySupplyWireDocument.Returned
): Boolean = occurrence.sameCallbackProof(other.occurrence)

internal fun QueryCallbackOwnerExecutionWireDocument.sameCallbackProof(
    other: QueryCallbackOwnerExecutionWireDocument
): Boolean =
    when (this) {
        QueryCallbackOwnerExecutionWireDocument.Unproven -> other == QueryCallbackOwnerExecutionWireDocument.Unproven
        is QueryCallbackOwnerExecutionWireDocument.Observed ->
            other is QueryCallbackOwnerExecutionWireDocument.Observed && sameCallbackProof(other)
        is QueryCallbackOwnerExecutionWireDocument.Immutable ->
            other is QueryCallbackOwnerExecutionWireDocument.Immutable && sameCallbackProof(other)
    }

internal fun QueryCallbackOwnerExecutionWireDocument.Observed.sameCallbackProof(
    other: QueryCallbackOwnerExecutionWireDocument.Observed
): Boolean = flow.sameCallbackProof(other.flow)

internal fun QueryCallbackOwnerExecutionWireDocument.Immutable.sameCallbackProof(
    other: QueryCallbackOwnerExecutionWireDocument.Immutable
): Boolean = flow.sameCallbackProof(other.flow) && owner.sameCallbackProof(other.owner)

internal fun QueryCallbackBodyBindingWireDocument.sameCallbackProof(
    other: QueryCallbackBodyBindingWireDocument
): Boolean =
    body.sameCallbackProof(other.body) &&
        supply.sameCallbackProof(other.supply) &&
        binding.sameCallbackProof(other.binding) &&
        obligations == other.obligations &&
        execution.sameCallbackProof(other.execution)
