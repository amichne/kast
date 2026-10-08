package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

private const val MAX_FACTORY_DEPTH = 64
private const val MAX_FACTORY_COUNT = 1000

enum class QueryCallbackFactoryFailure {
    INVALID_CAPTURE,
    INVALID_INVOCATION,
    WRONG_PRODUCER,
    INCOMPLETE_CAPTURES,
    FACTORY_LIMIT,
    BODY_CALL_INVENTORY_MISMATCH,
}

@ConsistentCopyVisibility
data class QueryCallbackFactoryReturnDocument
private constructor(
    val enclosing: ImpactDeclarationReferenceDocument,
    val invocation: ImpactInvocationReferenceDocument,
    val callable: QueryCallbackCallableDocument,
    val returnedValue: QueryImmutableCallbackValueDocument,
    val captures: BoundedProtocolList<QueryCallbackFactoryCaptureDocument>,
    val bodyCalls: QueryCallbackFactoryBodyCallsDocument,
) {
    private val nestedFactories =
        (listOf(returnedValue) + captures.values.flatMap { it.capturedValues() }).mapNotNull {
            (it.origin as? QueryImmutableCallbackValueOriginDocument.Returned)?.factory
        }
    val depth: Int = 1 + (nestedFactories.maxOfOrNull { it.depth } ?: 0)
    val factoryCount: Int = 1 + nestedFactories.sumOf { it.factoryCount }

    companion object {
        fun create(
            enclosing: ImpactDeclarationReferenceDocument,
            invocation: ImpactInvocationReferenceDocument,
            callable: QueryCallbackCallableDocument,
            returnedValue: QueryImmutableCallbackValueDocument,
            captures: BoundedProtocolList<QueryCallbackFactoryCaptureDocument>,
            bodyCalls: QueryCallbackFactoryBodyCallsDocument,
        ): Refinement<QueryCallbackFactoryReturnDocument, QueryCallbackFactoryFailure> {
            val signature =
                callable.compilerTarget.compilerEvidence.signature as? CompilerCallableSignatureDocument
                    ?: return invalid(QueryCallbackFactoryFailure.INVALID_INVOCATION)
            if (!callable.admitsFactoryCall(enclosing, invocation))
                return invalid(QueryCallbackFactoryFailure.INVALID_INVOCATION)
            if (!returnedValue.matchesProducer(invocation)) return invalid(QueryCallbackFactoryFailure.WRONG_PRODUCER)
            val positions = captures.values.map { it.binding.position.value }
            if (positions.sorted() != signature.valueParameters.values.indices.toList())
                return invalid(QueryCallbackFactoryFailure.INCOMPLETE_CAPTURES)
            if (captures.values.any { !it.admitsFactoryContext(enclosing, invocation, returnedValue) })
                return invalid(QueryCallbackFactoryFailure.INVALID_CAPTURE)
            if (!bodyCalls.admitsFactory(returnedValue, captures, enclosing.basis))
                return invalid(QueryCallbackFactoryFailure.BODY_CALL_INVENTORY_MISMATCH)
            val proof =
                QueryCallbackFactoryReturnDocument(enclosing, invocation, callable, returnedValue, captures, bodyCalls)
            if (proof.depth > MAX_FACTORY_DEPTH || proof.factoryCount > MAX_FACTORY_COUNT)
                return invalid(QueryCallbackFactoryFailure.FACTORY_LIMIT)
            return Refinement.Refined(proof)
        }
    }
}

private fun QueryCallbackCallableDocument.admitsFactoryCall(
    enclosing: ImpactDeclarationReferenceDocument,
    invocation: ImpactInvocationReferenceDocument,
): Boolean {
    if (!validCallable() || reference(enclosing.basis) != invocation.callable) return false
    return invocation.range.start.value < invocation.range.end.value &&
        invocation.range.start.value >= enclosing.range.start.value &&
        invocation.range.end.value <= enclosing.range.end.value
}

private fun QueryImmutableCallbackValueDocument.matchesProducer(
    invocation: ImpactInvocationReferenceDocument
): Boolean {
    if (source.enclosing != invocation.callable || destination.enclosing != invocation.callable) return false
    return destination.role == ImpactValueRoleDocument.ExpressionResult ||
        destination.role == ImpactValueRoleDocument.LocalRead
}

private fun QueryCallbackFactoryCaptureDocument.admitsFactoryContext(
    enclosing: ImpactDeclarationReferenceDocument,
    invocation: ImpactInvocationReferenceDocument,
    returned: QueryImmutableCallbackValueDocument,
): Boolean {
    if (binding.invocation != invocation || binding.callable.reference(enclosing.basis) != invocation.callable)
        return false
    val owner = binding.invocationOwner as? QueryCallbackBodyDocument.Named ?: return false
    if (owner.callable.reference(enclosing.basis) != enclosing) return false
    if (!admitsCapturedInvocations(returned, enclosing.basis)) return false
    val selected = selection
    return selected !is QueryCallbackSupplierSelectionDocument.Explicit || selected.argument.enclosing == enclosing
}

private fun QueryCallbackFactoryCaptureDocument.admitsCapturedInvocations(
    returned: QueryImmutableCallbackValueDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    when (val captured = content) {
        QueryCallbackFactoryCaptureContentDocument.Scalar -> true
        is QueryCallbackFactoryCaptureContentDocument.Callable -> {
            if (captured.invocations.values.isEmpty()) true
            else {
                val body = (returned.origin as? QueryImmutableCallbackValueOriginDocument.Anonymous)?.body
                body != null &&
                    captured.invocations.values.distinct().size == captured.invocations.values.size &&
                    captured.invocations.values.all {
                        it.admitFactoryCapture(body, binding.parameterIdentity(), basis) is Refinement.Refined
                    }
            }
        }
    }

private fun QueryCallbackFactoryCaptureDocument.capturedValues(): List<QueryImmutableCallbackValueDocument> =
    when (val captured = content) {
        QueryCallbackFactoryCaptureContentDocument.Scalar -> emptyList()
        is QueryCallbackFactoryCaptureContentDocument.Callable -> captured.values.values
    }

private fun invalid(cause: QueryCallbackFactoryFailure) = Refinement.Rejected(cause)
