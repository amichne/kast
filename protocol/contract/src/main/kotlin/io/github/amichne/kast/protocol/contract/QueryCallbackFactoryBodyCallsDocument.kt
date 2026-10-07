package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface QueryCallbackFactoryBodyCallDocument {
    val occurrence: RelationOccurrenceDocument

    data class Named(override val occurrence: RelationOccurrenceDocument, val target: QueryCallbackCallableDocument) :
        QueryCallbackFactoryBodyCallDocument

    data class Captured(
        val invocation: QueryCallbackInvocationDocument,
        val formal: QueryCallbackParameterIdentityDocument,
    ) : QueryCallbackFactoryBodyCallDocument {
        override val occurrence: RelationOccurrenceDocument
            get() = invocation.occurrence
    }

    data class Boundary(
        override val occurrence: RelationOccurrenceDocument,
        val callable: QuerySourceLessCallableDocument,
        val disposition: QuerySourceLessCallableDispositionDocument,
    ) : QueryCallbackFactoryBodyCallDocument
}

sealed interface QueryCallbackFactoryBodyCallsDocument {
    data object NotApplicable : QueryCallbackFactoryBodyCallsDocument

    @ConsistentCopyVisibility
    data class Exhaustive
    private constructor(
        val body: QueryCallbackBodyDocument.Anonymous,
        val calls: BoundedProtocolList<QueryCallbackFactoryBodyCallDocument>,
    ) : QueryCallbackFactoryBodyCallsDocument {
        companion object {
            fun create(
                body: QueryCallbackBodyDocument.Anonymous,
                calls: BoundedProtocolList<QueryCallbackFactoryBodyCallDocument>,
            ): Refinement<Exhaustive, QueryCallbackFactoryFailure> {
                if (!body.validBody() || calls.values.map { it.occurrence }.distinct().size != calls.values.size)
                    return rejectedBodyCalls()
                if (calls.values.any { !body.occurrence.contains(it.occurrence) || !it.admitsBody(body) })
                    return rejectedBodyCalls()
                return Refinement.Refined(Exhaustive(body, calls))
            }
        }
    }
}

internal fun QueryCallbackFactoryBodyCallsDocument.admitsFactory(
    returned: QueryImmutableCallbackValueDocument,
    captures: BoundedProtocolList<QueryCallbackFactoryCaptureDocument>,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    when (val origin = returned.origin) {
        is QueryImmutableCallbackValueOriginDocument.Anonymous -> {
            val inventory = this as? QueryCallbackFactoryBodyCallsDocument.Exhaustive
            inventory != null && inventory.body == origin.body && inventory.admitsCaptures(captures, basis)
        }
        is QueryImmutableCallbackValueOriginDocument.Named,
        is QueryImmutableCallbackValueOriginDocument.Returned ->
            this == QueryCallbackFactoryBodyCallsDocument.NotApplicable
    }

private fun QueryCallbackFactoryBodyCallsDocument.Exhaustive.admitsCaptures(
    captures: BoundedProtocolList<QueryCallbackFactoryCaptureDocument>,
    basis: ImpactSemanticBasisDocument,
): Boolean {
    val expected =
        captures.values.flatMap { capture ->
            val content = capture.content as? QueryCallbackFactoryCaptureContentDocument.Callable
            content?.invocations?.values.orEmpty().map { it to capture.binding.parameterIdentity() }
        }
    val actual = calls.values.filterIsInstance<QueryCallbackFactoryBodyCallDocument.Captured>()
    return actual.size == expected.size &&
        actual.all { call ->
            call.invocation.admitFactoryCapture(body, call.formal, basis) is Refinement.Refined &&
                expected.any { (invocation, formal) ->
                    invocation == call.invocation && formal.sameParameter(call.formal)
                }
        }
}

private fun QueryCallbackFactoryBodyCallDocument.admitsBody(body: QueryCallbackBodyDocument.Anonymous): Boolean =
    when (this) {
        is QueryCallbackFactoryBodyCallDocument.Named -> target.validCallable()
        is QueryCallbackFactoryBodyCallDocument.Captured -> invocation.owner == body && formal.validParameter()
        is QueryCallbackFactoryBodyCallDocument.Boundary ->
            when (callable.moduleKind) {
                QuerySourceLessCallableModuleKindDocument.BUILTINS ->
                    disposition == QuerySourceLessCallableDispositionDocument.BUILTIN_BOUNDARY
                QuerySourceLessCallableModuleKindDocument.LIBRARY ->
                    disposition == QuerySourceLessCallableDispositionDocument.LIBRARY_POLICY_EXCLUDED
            }
    }

private fun rejectedBodyCalls() = Refinement.Rejected(QueryCallbackFactoryFailure.BODY_CALL_INVENTORY_MISMATCH)
