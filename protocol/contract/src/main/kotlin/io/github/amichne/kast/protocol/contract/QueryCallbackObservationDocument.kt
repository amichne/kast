package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

enum class QueryCallbackDocumentFailure {
    MEANING_MISMATCH,
    CALLABLE_DECLARATION_MISMATCH,
    ANONYMOUS_IDENTITY_MISMATCH,
    CALLBACK_CONTAINMENT_MISMATCH,
    EXCLUDED_BOUNDARY_MISMATCH,
    FLOW_BODY_MISMATCH,
    BINDING_MISMATCH,
    PARAMETER_POSITION_MISMATCH,
    INVOCATION_OWNER_MISMATCH,
    MISSING_OBLIGATION,
    DUPLICATE_INVOCATION,
    TRANSFER_PROOF_MISMATCH,
    OWNER_BINDING_MISMATCH,
    DUPLICATE_OWNER_BINDING,
}

@Serializable
enum class QueryCallbackExclusionReasonDocument {
    RETURNED_CALLBACK,
    NON_INLINE_ARGUMENT,
    NOINLINE_ARGUMENT,
    CROSSINLINE_ARGUMENT,
    STORED_CALLBACK,
}

@Serializable
enum class QueryCallbackNamedUnavailableCauseDocument {
    UNRESOLVED_ARGUMENT_MAPPING,
    UNSUPPORTED_BOUNDARY,
}

/** Named-call ownership and possible callback invocation remain separate facts. */
sealed interface QueryCallbackNamedPolicyDocument {
    data object AdmittedInline : QueryCallbackNamedPolicyDocument

    data class Unavailable(val cause: QueryCallbackNamedUnavailableCauseDocument) : QueryCallbackNamedPolicyDocument

    data class Excluded(
        val reason: QueryCallbackExclusionReasonDocument,
        val excludedBoundary: RelationOccurrenceDocument,
    ) : QueryCallbackNamedPolicyDocument
}

/** Exact source reference and detached compiler facts; does not assert membership in the search domain. */
data class QueryCallbackCallableDocument(
    val declaration: RelationOccurrenceDocument,
    val compilerTarget: QueryExcludedCompilerTargetDocument,
)

@ConsistentCopyVisibility
data class QueryCallbackObservationDocument
private constructor(
    val occurrence: RelationOccurrenceDocument,
    val target: QueryCallbackCallableDocument,
    val lexicalOwner: QueryCallbackCallableDocument,
    val callbackBody: RelationOccurrenceDocument,
    val namedPolicy: QueryCallbackNamedPolicyDocument,
    val flow: QueryCallbackFlowDocument,
    val relation: RelationKindDocument,
    val requestedDomain: QueryRelationRequestedDomainDocument,
    val effectiveDomain: QueryRelationDomainDocument,
    val domainFingerprint: QueryRelationDomainFingerprint,
) {
    companion object {
        fun create(
            occurrence: RelationOccurrenceDocument,
            target: QueryCallbackCallableDocument,
            lexicalOwner: QueryCallbackCallableDocument,
            callbackBody: RelationOccurrenceDocument,
            namedPolicy: QueryCallbackNamedPolicyDocument,
            flow: QueryCallbackFlowDocument,
            relation: RelationKindDocument,
            requestedDomain: QueryRelationRequestedDomainDocument,
            effectiveDomain: QueryRelationDomainDocument,
            domainFingerprint: QueryRelationDomainFingerprint,
        ): Refinement<QueryCallbackObservationDocument, QueryCallbackDocumentFailure> {
            val value =
                QueryCallbackObservationDocument(
                    occurrence,
                    target,
                    lexicalOwner,
                    callbackBody,
                    namedPolicy,
                    flow,
                    relation,
                    requestedDomain,
                    effectiveDomain,
                    domainFingerprint,
                )
            return when (val admitted = value.admit()) {
                is Refinement.Refined -> Refinement.Refined(value)
                is Refinement.Rejected -> admitted
            }
        }
    }
}

data class QueryWalkCallbackObservationDocument(
    val subject: QueryReferenceDocument.ExactSymbol,
    val depth: TraversalDepthDocument,
    val observation: QueryCallbackObservationDocument,
)

@Serializable
enum class QueryCallbackFlowCauseDocument {
    STORED_CALLBACK,
    RETURNED_CALLBACK,
    UNSUPPORTED_CALLBACK_SUPPLY,
    ANONYMOUS_IDENTITY_UNAVAILABLE,
    UNRESOLVED_ARGUMENT_MAPPING,
    UNRESOLVED_PARAMETER_REFERENCE,
    EXTERNAL_CALLABLE,
    OUTSIDE_DOMAIN,
    PARAMETER_ESCAPES,
    NESTED_CALLBACK_EXECUTION,
    NO_INVOCATION_PROVEN,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
}

@Serializable
enum class QueryCallbackFlowFailureDocument {
    BASIS_MISMATCH,
    BODY_OUTSIDE_ARGUMENT,
    PARAMETER_OUTSIDE_CALLABLE,
    INVALID_PARAMETER_POSITION,
    INVOCATION_OUTSIDE_CALLABLE,
    INVOCATION_OUTSIDE_OWNER,
    UNBOUND_INVOCATION,
    DUPLICATE_INVOCATION,
    MISSING_OBLIGATION,
    INVOCATION_OUTSIDE_SUPPLYING_OWNER,
    UNSUPPORTED_CALLABLE_TRANSFER,
    INVALID_CALLABLE_TRANSFER_PATH,
    CALLABLE_TRANSFER_BINDING_MISMATCH,
    OWNER_BINDING_MISMATCH,
    DUPLICATE_OWNER_BINDING,
}

sealed interface QueryCallbackBodyDocument {
    data class Named(val callable: QueryCallbackCallableDocument) : QueryCallbackBodyDocument

    data class Anonymous(
        val occurrence: RelationOccurrenceDocument,
        val compilerEvidence: CompilerSymbolEvidenceDocument,
    ) : QueryCallbackBodyDocument
}

sealed interface QueryCallbackBindingDocument {
    data class Bound(
        val invocation: ImpactInvocationReferenceDocument,
        val invocationOccurrence: RelationOccurrenceDocument,
        val invocationOwner: QueryCallbackBodyDocument,
        val callable: QueryCallbackCallableDocument,
        val position: ProtocolOffset,
        val parameter: RelationOccurrenceDocument,
    ) : QueryCallbackBindingDocument

    data class Unavailable(val cause: QueryCallbackFlowCauseDocument) : QueryCallbackBindingDocument
}

data class QueryCallbackInvocationDocument(
    val occurrence: RelationOccurrenceDocument,
    val owner: QueryCallbackBodyDocument,
    val callableTransfers: BoundedProtocolList<ImpactCompilerTransferDocument>,
)

sealed interface QueryCallbackFlowDocument {
    data class Observed(
        val basis: ImpactSemanticBasisDocument,
        val body: QueryCallbackBodyDocument.Anonymous,
        val binding: QueryCallbackBindingDocument,
        val invocations: BoundedProtocolList<QueryCallbackInvocationDocument>,
        val obligations: BoundedProtocolList<QueryCallbackFlowCauseDocument>,
        val ownerBindings: BoundedProtocolList<QueryCallbackBodyBindingDocument>,
    ) : QueryCallbackFlowDocument

    data class Unavailable(val cause: QueryCallbackFlowCauseDocument) : QueryCallbackFlowDocument

    data class ContractRejected(val cause: QueryCallbackFlowFailureDocument) : QueryCallbackFlowDocument
}
