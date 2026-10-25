package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun QueryCallbackBodyBindingDocument.admitExecution(
    basis: ImpactSemanticBasisDocument,
    depth: Int,
): Refinement<Unit, QueryCallbackDocumentFailure> =
    when (val execution = execution) {
        QueryCallbackOwnerExecutionDocument.Unproven -> Refinement.Refined(Unit)
        is QueryCallbackOwnerExecutionDocument.Immutable -> admitImmutableExecution(execution, basis)
        is QueryCallbackOwnerExecutionDocument.Observed -> {
            val flow = execution.flow
            if (flow.body != body || flow.binding != binding || flow.basis != basis)
                Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
            else if (!obligations.values.containsAll(flow.obligations.values))
                Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
            else flow.admitObservedFlow(depth + 1)
        }
    }

internal fun QueryCallbackFlowDocument.Observed.admitsExecutionOwner(owner: QueryCallbackBodyDocument): Boolean =
    QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION in obligations.values ||
        ownerBindings.values.admitsExecutionOwner(owner)

internal fun List<QueryCallbackBodyBindingDocument>.admitsExecutionOwner(owner: QueryCallbackBodyDocument): Boolean =
    admittedOwnerRoot(owner) is Refinement.Refined

internal fun List<QueryCallbackBodyBindingDocument>.admitsExecutionOwner(
    owner: QueryCallbackBodyDocument,
    expected: QueryCallbackCallableDocument,
): Boolean =
    when (val root = admittedOwnerRoot(owner)) {
        is Refinement.Rejected -> false
        is Refinement.Refined ->
            root.value.compilerTarget == expected.compilerTarget &&
                root.value.declaration.sameSite(expected.declaration)
    }

internal fun List<QueryCallbackBodyBindingDocument>.admittedOwnerRoot(
    owner: QueryCallbackBodyDocument
): Refinement<QueryCallbackCallableDocument, QueryCallbackDocumentFailure> {
    val binding =
        singleOrNull { it.body == owner }
            ?: return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    if (binding.obligations.values.isNotEmpty())
        return Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    return when (val execution = binding.execution) {
        QueryCallbackOwnerExecutionDocument.Unproven ->
            Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
        is QueryCallbackOwnerExecutionDocument.Observed -> execution.flow.admittedExecutionRoot()
        is QueryCallbackOwnerExecutionDocument.Immutable ->
            if (execution.flow.obligations.values.isEmpty()) Refinement.Refined(execution.owner.callable)
            else Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    }
}

private fun QueryCallbackFlowDocument.Observed.admittedExecutionRoot():
    Refinement<QueryCallbackCallableDocument, QueryCallbackDocumentFailure> {
    if (obligations.values.isNotEmpty()) return Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    val owner =
        when (val binding = binding) {
            is QueryCallbackBindingDocument.Bound -> binding.invocationOwner
            is QueryCallbackBindingDocument.Direct -> binding.owner
            is QueryCallbackBindingDocument.DependencyContract -> binding.owner
            is QueryCallbackBindingDocument.DeferredDependency -> QueryCallbackBodyDocument.Named(binding.owner)
            is QueryCallbackBindingDocument.Default,
            is QueryCallbackBindingDocument.Unavailable ->
                return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
        }
    return when (owner) {
        is QueryCallbackBodyDocument.Named -> Refinement.Refined(owner.callable)
        is QueryCallbackBodyDocument.Anonymous -> ownerBindings.values.admittedOwnerRoot(owner)
    }
}

internal fun QueryCallbackFlowDocument.Observed.admitsOwnerExecutionScan(
    expected: QueryCallbackInvocationScanDocument
): Boolean =
    scan == expected ||
        scan == QueryCallbackInvocationScanDocument.INCOMPLETE &&
            QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION in obligations.values &&
            obligations.values.any { it != QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION }

internal fun QueryCallbackFlowDocument.Observed.admitLexicalSupplier(
    lexicalOwner: QueryCallbackCallableDocument
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val supplier =
        when (val binding = binding) {
            is QueryCallbackBindingDocument.Bound -> binding.invocationOwner
            is QueryCallbackBindingDocument.Direct -> binding.owner
            is QueryCallbackBindingDocument.DependencyContract -> binding.owner
            is QueryCallbackBindingDocument.DeferredDependency ->
                return if (lexicalOwner.declaration.contains(binding.owner.declaration)) Refinement.Refined(Unit)
                else Refinement.Rejected(QueryCallbackDocumentFailure.INVOCATION_OWNER_MISMATCH)
            is QueryCallbackBindingDocument.Default,
            is QueryCallbackBindingDocument.Unavailable -> return Refinement.Refined(Unit)
        }
    val matchesRoot =
        when (supplier) {
            is QueryCallbackBodyDocument.Named ->
                supplier.callable.compilerTarget == lexicalOwner.compilerTarget &&
                    supplier.callable.declaration.sameSite(lexicalOwner.declaration)
            is QueryCallbackBodyDocument.Anonymous -> ownerBindings.values.admitsExecutionOwner(supplier, lexicalOwner)
        }
    if (!matchesRoot && QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION !in obligations.values)
        return Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    return when (val owner = supplier.admitOwner()) {
        is Refinement.Rejected -> owner
        is Refinement.Refined ->
            if (lexicalOwner.declaration.contains(owner.value)) Refinement.Refined(Unit)
            else Refinement.Rejected(QueryCallbackDocumentFailure.INVOCATION_OWNER_MISMATCH)
    }
}

internal fun QueryCallbackFlowDocument.Observed.admitExecutionInventory():
    Refinement<Unit, QueryCallbackDocumentFailure> = ownerBindings.values.admitExecutionInventory(setOf(body))

internal fun QueryImmutableCallbackFlowDocument.admitExecutionInventory():
    Refinement<Unit, QueryCallbackDocumentFailure> {
    val bodies =
        when (val origin = sourceValue.origin) {
            is QueryImmutableCallbackValueOriginDocument.Anonymous -> setOf(origin.body)
            is QueryImmutableCallbackValueOriginDocument.Named,
            is QueryImmutableCallbackValueOriginDocument.Returned -> emptySet()
        }
    return executionOwnerBindings().admitExecutionInventory(bodies)
}

internal fun List<QueryCallbackBodyBindingDocument>.admitExecutionInventory(
    roots: Set<QueryCallbackBodyDocument.Anonymous>
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val remaining = ArrayDeque<OwnerExecutionFrame>()
    for (owner in this) remaining.addLast(OwnerExecutionFrame(owner, roots, 1))
    var count = 0
    while (remaining.isNotEmpty()) {
        val (owner, ancestors, depth) = remaining.removeLast()
        if (
            ++count > QueryCallbackOwnerExecutionLimits.MAXIMUM_COUNT ||
                depth >= QueryCallbackOwnerExecutionLimits.EXCLUSIVE_DEPTH
        )
            return Refinement.Rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
        if (owner.obligations.values.isEmpty() && owner.body in ancestors)
            return Refinement.Rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
        val children =
            when (val execution = owner.execution) {
                QueryCallbackOwnerExecutionDocument.Unproven -> emptyList()
                is QueryCallbackOwnerExecutionDocument.Observed -> execution.flow.ownerBindings.values
                is QueryCallbackOwnerExecutionDocument.Immutable -> execution.flow.executionOwnerBindings()
            }
        for (child in children) remaining.addLast(OwnerExecutionFrame(child, ancestors + owner.body, depth + 1))
    }
    return Refinement.Refined(Unit)
}

private data class OwnerExecutionFrame(
    val owner: QueryCallbackBodyBindingDocument,
    val ancestors: Set<QueryCallbackBodyDocument.Anonymous>,
    val depth: Int,
)

private fun QueryCallbackBodyBindingDocument.admitImmutableExecution(
    execution: QueryCallbackOwnerExecutionDocument.Immutable,
    basis: ImpactSemanticBasisDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val unavailable =
        binding as? QueryCallbackBindingDocument.Unavailable
            ?: return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    if (unavailable.cause !in immutableSupplyCauses)
        return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    val flow = execution.flow
    val origin =
        flow.sourceValue.origin as? QueryImmutableCallbackValueOriginDocument.Anonymous
            ?: return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    if (
        origin.body != body ||
            !execution.owner.callable.validCallable() ||
            flow.sourceValue.source.enclosing != execution.owner.callable.reference(basis)
    )
        return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    if (!obligations.values.containsAll(flow.obligations.values))
        return Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    return Refinement.Refined(Unit)
}

internal fun QueryCallbackOwnerExecutionDocument.dischargesSupplyCause(cause: QueryCallbackFlowCauseDocument): Boolean =
    this is QueryCallbackOwnerExecutionDocument.Immutable &&
        flow.obligations.values.isEmpty() &&
        cause in immutableSupplyCauses

private val immutableSupplyCauses =
    setOf(
        QueryCallbackFlowCauseDocument.STORED_CALLBACK,
        QueryCallbackFlowCauseDocument.RETURNED_CALLBACK,
        QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY,
    )
