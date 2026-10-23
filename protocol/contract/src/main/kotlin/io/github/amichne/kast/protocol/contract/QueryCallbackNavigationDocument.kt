package io.github.amichne.kast.protocol.contract

/** Navigation is a projection of static callback proof, never an extra named CALLERS relation. */
sealed interface QueryCallbackNavigationDocument {
    data object NotComputed : QueryCallbackNavigationDocument

    data class Proven(val steps: BoundedProtocolList<QueryCallbackNavigationStepDocument>) :
        QueryCallbackNavigationDocument

    /** The original flow retains the occurrence-specific obligations and all witnesses behind this prefix. */
    data class Unproven(
        val cause: QueryCallbackGraphCauseDocument,
        val prefix: BoundedProtocolList<QueryCallbackNavigationStepDocument>,
    ) : QueryCallbackNavigationDocument
}

sealed interface QueryCallbackNavigationNodeDocument {
    data class Callable(val body: QueryCallbackBodyDocument) : QueryCallbackNavigationNodeDocument

    /** The supplier is part of formal identity; equal declarations at distinct calls must not merge. */
    data class Formal(
        val parameter: QueryCallbackParameterIdentityDocument,
        val supplier: QueryCallbackBindingDocument.Bound,
    ) : QueryCallbackNavigationNodeDocument
}

sealed interface QueryCallbackNavigationEvidenceDocument {
    data class Supply(val binding: QueryCallbackBindingDocument.Bound, val body: QueryCallbackBodyDocument.Anonymous) :
        QueryCallbackNavigationEvidenceDocument

    data class Forward(val forwarding: QueryCallbackForwardingDocument) : QueryCallbackNavigationEvidenceDocument

    data class Invoke(val invocation: QueryCallbackInvocationDocument) : QueryCallbackNavigationEvidenceDocument

    data class DirectInvoke(val binding: QueryCallbackBindingDocument.Direct) : QueryCallbackNavigationEvidenceDocument

    data class FunctionInvocation(
        val occurrence: RelationOccurrenceDocument,
        val policy: QueryCallbackNamedPolicyDocument,
    ) : QueryCallbackNavigationEvidenceDocument
}

/** Depth counts Supply, Forward, Invoke and FunctionInvocation edges individually, beginning at one. */
data class QueryCallbackNavigationStepDocument(
    val depth: TraversalDepthDocument,
    val source: QueryCallbackNavigationNodeDocument,
    val target: QueryCallbackNavigationNodeDocument,
    val evidence: QueryCallbackNavigationEvidenceDocument,
)
