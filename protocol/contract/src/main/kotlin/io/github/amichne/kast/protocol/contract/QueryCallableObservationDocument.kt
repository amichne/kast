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
    data class DirectInvocations(val invocations: BoundedProtocolList<QueryImmutableCallbackUseDocument.Direct>) :
        QueryCallableTargetDocument

    data class UnavailableSupply(val obligations: QueryCallbackGraphObligationsDocument) : QueryCallableTargetDocument

    data class CallbackSupplies(
        val supplies: BoundedProtocolList<QueryImmutableCallbackUseDocument.Supplied>,
        val formals: BoundedProtocolList<QueryCallbackParameterIdentityDocument>,
    ) : QueryCallableTargetDocument

    data class UnavailableReference(val cause: QueryCallbackFlowCauseDocument) : QueryCallableTargetDocument

    data class NamedReference(val reference: QueryNamedCallbackReferenceDocument) : QueryCallableTargetDocument

    data class ParameterInvocation(
        val parameter: QueryCallbackParameterIdentityDocument,
        val suppliers: QueryCallbackSupplierInventoryDocument,
        val invocation: QueryCallbackInvocationDocument,
    ) : QueryCallableTargetDocument

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
    fun admitsDomain(domain: QueryRelationDomainDocument, fingerprint: QueryRelationDomainFingerprint): Boolean =
        target.admitsFactoryPolicy(domain) &&
            when (val value = target) {
                is QueryCallableTargetDocument.DirectInvocations,
                is QueryCallableTargetDocument.UnavailableSupply,
                is QueryCallableTargetDocument.CallbackSupplies,
                is QueryCallableTargetDocument.UnavailableReference,
                is QueryCallableTargetDocument.NamedReference -> true
                is QueryCallableTargetDocument.ParameterInvocation ->
                    when (val suppliers = value.suppliers) {
                        is QueryCallbackSupplierInventoryDocument.Unavailable -> true
                        is QueryCallbackSupplierInventoryDocument.Exhaustive -> suppliers.domain == fingerprint
                    }
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
            if (!admittedCallableOwner(lexicalOwner, owner, occurrence))
                return Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_BODY)
            if (
                target is QueryCallableTargetDocument.DirectInvocations &&
                    !target.admitsDirectInvocations(occurrence, body)
            )
                return Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_BOUNDARY)
            if (
                target is QueryCallableTargetDocument.CallbackSupplies &&
                    !target.admitsSelectedSupplies(occurrence, body)
            )
                return Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_BOUNDARY)
            if (
                target is QueryCallableTargetDocument.NamedReference &&
                    !target.reference.admits(occurrence, lexicalOwner)
            )
                return Refinement.Rejected(QueryCallableObservationDocumentFailure.INVALID_BOUNDARY)
            if (
                target is QueryCallableTargetDocument.ParameterInvocation &&
                    !target.admitsParameter(owner, occurrence, body)
            )
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

private fun QueryCallableTargetDocument.ParameterInvocation.admitsParameter(
    owner: RelationOccurrenceDocument,
    occurrence: RelationOccurrenceDocument,
    body: QueryCallbackBodyDocument,
): Boolean {
    if (!parameter.validParameter() || !parameter.callable.declaration.contains(owner)) return false
    if (!invocation.admitsObservedParameter(parameter, occurrence, body)) return false
    return when (val proof = suppliers) {
        is QueryCallbackSupplierInventoryDocument.Unavailable -> true
        is QueryCallbackSupplierInventoryDocument.Exhaustive -> proof.root.sameParameter(parameter)
    }
}

private fun QueryImmutableCallbackUseDocument.Supplied.admitsSelectedSupply(
    occurrence: RelationOccurrenceDocument,
    body: QueryCallbackBodyDocument,
): Boolean {
    val binding = supplier.binding
    if (binding.invocationOwner !is QueryCallbackBodyDocument.Named || binding.invocationOwner != body) return false
    if (!binding.invocationOccurrence.sameSite(occurrence)) return false
    return CallbackInvocationRouteProof(value.source.enclosing.basis, invocations.values, emptyList(), forwarding)
        .admitParameterInvocations(binding.parameterIdentity(), binding.invocation.callable) is Refinement.Refined
}

private fun QueryCallableTargetDocument.CallbackSupplies.admitsSelectedSupplies(
    occurrence: RelationOccurrenceDocument,
    body: QueryCallbackBodyDocument,
): Boolean {
    if (supplies.values.isEmpty() || supplies.values.distinct().size != supplies.values.size) return false
    if (formals.values.any { !it.validParameter() }) return false
    val required = formals.values.map { it.formalSite() }
    if (
        required.distinct().size != required.size ||
            required.toSet() != supplies.values.map { it.supplier.binding.parameterIdentity().formalSite() }.toSet()
    )
        return false
    val invocation = supplies.values.first().supplier.binding.invocation
    return supplies.values.all {
        it.supplier.binding.invocation == invocation && it.admitsSelectedSupply(occurrence, body)
    }
}

private fun QueryCallableTargetDocument.DirectInvocations.admitsDirectInvocations(
    occurrence: RelationOccurrenceDocument,
    body: QueryCallbackBodyDocument,
): Boolean {
    val values = invocations.values
    if (values.isEmpty() || values.distinct().size != values.size) return false
    val binding = values.first().binding
    if (binding.owner != body || !binding.occurrence.sameSite(occurrence)) return false
    return values.all { it.binding == binding && it.admitDirect(it.value.source.enclosing.basis) is Refinement.Refined }
}

private fun admittedCallableOwner(
    lexicalOwner: QueryCallbackCallableDocument,
    owner: RelationOccurrenceDocument,
    occurrence: RelationOccurrenceDocument,
): Boolean =
    QueryCallbackBodyDocument.Named(lexicalOwner).admitOwner() is Refinement.Refined &&
        lexicalOwner.declaration.contains(owner) &&
        owner.contains(occurrence)
