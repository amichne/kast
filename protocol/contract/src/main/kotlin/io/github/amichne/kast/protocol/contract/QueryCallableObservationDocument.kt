package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

private const val MAX_SOURCELESS_MODULE_NAME_LENGTH = 512

@Serializable
enum class QuerySourceLessCallableOriginDocument {
    SOURCE,
    SOURCE_MEMBER_GENERATED,
    LIBRARY,
    JAVA_SOURCE,
    JAVA_LIBRARY,
    SAM_CONSTRUCTOR,
    TYPEALIASED_CONSTRUCTOR,
    INTERSECTION_OVERRIDE,
    SUBSTITUTION_OVERRIDE,
    DELEGATED,
    JAVA_SYNTHETIC_PROPERTY,
    PROPERTY_BACKING_FIELD,
    PLUGIN,
    JS_DYNAMIC,
    NATIVE_FORWARD_DECLARATION,
}

@Serializable
enum class QuerySourceLessCallableModuleKindDocument {
    BUILTINS,
    LIBRARY,
}

@Serializable
enum class QuerySourceLessCallableDispositionDocument {
    BUILTIN_BOUNDARY,
    LIBRARY_POLICY_EXCLUDED,
    LIBRARY_SOURCE_UNAVAILABLE,
}

enum class QueryCallableObservationDocumentFailure {
    INVALID_BODY,
    INVALID_PARAMETER,
    INVALID_BOUNDARY,
    INVALID_MODULE_NAME,
}

/** A compiler-resolved source-less target retains its exact signature, origin and module classification. */
@ConsistentCopyVisibility
data class QuerySourceLessCallableDocument
private constructor(
    val compilerEvidence: CompilerSymbolEvidenceDocument,
    val kind: SymbolKindDocument,
    val origin: QuerySourceLessCallableOriginDocument,
    val moduleKind: QuerySourceLessCallableModuleKindDocument,
    val moduleName: ProtocolText,
) {
    companion object {
        fun create(
            compilerEvidence: CompilerSymbolEvidenceDocument,
            kind: SymbolKindDocument,
            origin: QuerySourceLessCallableOriginDocument,
            moduleKind: QuerySourceLessCallableModuleKindDocument,
            moduleName: ProtocolText,
        ): Refinement<QuerySourceLessCallableDocument, QueryCallableObservationDocumentFailure> =
            when {
                moduleName.value.isBlank() ||
                    moduleName.value.length > MAX_SOURCELESS_MODULE_NAME_LENGTH ||
                    moduleName.value.any { it.isISOControl() } ->
                    Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_MODULE_NAME)
                !compilerEvidence.signature.supports(kind) ||
                    kind !in
                        setOf(
                            SymbolKindDocument.FUNCTION,
                            SymbolKindDocument.CONSTRUCTOR,
                            SymbolKindDocument.PROPERTY,
                        ) -> Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_BOUNDARY)
                moduleKind == QuerySourceLessCallableModuleKindDocument.LIBRARY &&
                    origin !in
                        setOf(
                            QuerySourceLessCallableOriginDocument.LIBRARY,
                            QuerySourceLessCallableOriginDocument.JAVA_LIBRARY,
                        ) -> Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_BOUNDARY)
                else ->
                    Refinement.Refined(
                        QuerySourceLessCallableDocument(compilerEvidence, kind, origin, moduleKind, moduleName)
                    )
            }
    }
}

sealed interface QueryCallableTargetDocument {
    data class ParameterInvocation(val parameter: QueryCallbackParameterIdentityDocument) : QueryCallableTargetDocument

    data class SourceLess(
        val callable: QuerySourceLessCallableDocument,
        val disposition: QuerySourceLessCallableDispositionDocument,
    ) : QueryCallableTargetDocument
}

@ConsistentCopyVisibility
data class QueryCallableObservationDocument
private constructor(
    val occurrence: RelationOccurrenceDocument,
    val lexicalOwner: QueryCallbackCallableDocument,
    val body: QueryCallbackBodyDocument,
    val target: QueryCallableTargetDocument,
) {
    fun admitsDomain(domain: QueryRelationDomainDocument): Boolean =
        when (val value = target) {
            is QueryCallableTargetDocument.ParameterInvocation -> true
            is QueryCallableTargetDocument.SourceLess ->
                when (value.disposition) {
                    QuerySourceLessCallableDispositionDocument.BUILTIN_BOUNDARY ->
                        value.callable.moduleKind == QuerySourceLessCallableModuleKindDocument.BUILTINS
                    QuerySourceLessCallableDispositionDocument.LIBRARY_POLICY_EXCLUDED ->
                        value.callable.moduleKind == QuerySourceLessCallableModuleKindDocument.LIBRARY &&
                            domain.libraries == QueryDiscoveryInclusionPolicyDocument.EXCLUDE
                    QuerySourceLessCallableDispositionDocument.LIBRARY_SOURCE_UNAVAILABLE ->
                        value.callable.moduleKind == QuerySourceLessCallableModuleKindDocument.LIBRARY &&
                            domain.libraries == QueryDiscoveryInclusionPolicyDocument.INCLUDE
                }
        }

    companion object {
        fun create(
            occurrence: RelationOccurrenceDocument,
            lexicalOwner: QueryCallbackCallableDocument,
            body: QueryCallbackBodyDocument,
            target: QueryCallableTargetDocument,
        ): Refinement<QueryCallableObservationDocument, QueryCallableObservationDocumentFailure> {
            val owner =
                when (val admitted = body.admitOwner()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_BODY)
                }
            if (
                QueryCallbackBodyDocument.Named(lexicalOwner).admitOwner() is Refinement.Rejected ||
                    !lexicalOwner.declaration.contains(owner) ||
                    !owner.contains(occurrence)
            )
                return Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_BODY)
            if (target is QueryCallableTargetDocument.ParameterInvocation && !target.parameter.validParameter())
                return Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_PARAMETER)
            return Refinement.Refined(QueryCallableObservationDocument(occurrence, lexicalOwner, body, target))
        }
    }
}

/** A frontier-relative callable observation remains attached to the question that produced it. */
data class QueryWalkCallableObservationDocument(
    val subject: QueryReferenceDocument.ExactSymbol,
    val depth: TraversalDepthDocument,
    val observation: QueryCallableObservationDocument,
    val requestedDomain: QueryRelationRequestedDomainDocument,
    val effectiveDomain: QueryRelationDomainDocument,
    val domainFingerprint: QueryRelationDomainFingerprint,
)
