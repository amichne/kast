package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableDispositionDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableModuleKindDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableOriginDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallableObservationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationCallableTarget
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableDisposition
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin

internal fun RelationCallableObservation.projectCallableObservation(
    authority: QueryReferenceAuthority
): QueryCallableObservationDocument? {
    val projection = CallbackProjection(authority, basis)
    val projectedTarget = target.projectCallableTarget(projection) ?: return null
    return QueryCallableObservationDocument.create(
            projection.occurrence(occurrence) ?: return null,
            projection.callable(lexicalOwner) ?: return null,
            projection.body(body) ?: return null,
            projectedTarget,
        )
        .callableValue()
}

private fun RelationCallableTarget.projectCallableTarget(projection: CallbackProjection): QueryCallableTargetDocument? =
    when (this) {
        is RelationCallableTarget.ParameterInvocation ->
            projection.parameter(parameter)?.let { QueryCallableTargetDocument.ParameterInvocation(it) }
        is RelationCallableTarget.SourceLess ->
            callable.protocolDocument()?.let {
                QueryCallableTargetDocument.SourceLess(it, disposition.protocolDocument())
            }
    }

private fun SourceLessCallable.protocolDocument(): QuerySourceLessCallableDocument? {
    val evidence =
        CompilerSymbolEvidenceDocument.restore(
                ProtocolText.parse(compilerIdentity.value).callableValue() ?: return null,
                signature.protocolDocument() ?: return null,
            )
            .callableValue() ?: return null
    return QuerySourceLessCallableDocument.create(
            evidence,
            kind.protocolKind(),
            origin.protocolDocument(),
            moduleKind.protocolDocument(),
            ProtocolText.parse(moduleName.value).callableValue() ?: return null,
        )
        .callableValue()
}

private fun SourceLessCallableOrigin.protocolDocument(): QuerySourceLessCallableOriginDocument =
    when (this) {
        SourceLessCallableOrigin.SOURCE -> QuerySourceLessCallableOriginDocument.SOURCE
        SourceLessCallableOrigin.SOURCE_MEMBER_GENERATED ->
            QuerySourceLessCallableOriginDocument.SOURCE_MEMBER_GENERATED
        SourceLessCallableOrigin.LIBRARY -> QuerySourceLessCallableOriginDocument.LIBRARY
        SourceLessCallableOrigin.JAVA_SOURCE -> QuerySourceLessCallableOriginDocument.JAVA_SOURCE
        SourceLessCallableOrigin.JAVA_LIBRARY -> QuerySourceLessCallableOriginDocument.JAVA_LIBRARY
        SourceLessCallableOrigin.SAM_CONSTRUCTOR -> QuerySourceLessCallableOriginDocument.SAM_CONSTRUCTOR
        SourceLessCallableOrigin.TYPEALIASED_CONSTRUCTOR ->
            QuerySourceLessCallableOriginDocument.TYPEALIASED_CONSTRUCTOR
        SourceLessCallableOrigin.INTERSECTION_OVERRIDE -> QuerySourceLessCallableOriginDocument.INTERSECTION_OVERRIDE
        SourceLessCallableOrigin.SUBSTITUTION_OVERRIDE -> QuerySourceLessCallableOriginDocument.SUBSTITUTION_OVERRIDE
        SourceLessCallableOrigin.DELEGATED -> QuerySourceLessCallableOriginDocument.DELEGATED
        SourceLessCallableOrigin.JAVA_SYNTHETIC_PROPERTY ->
            QuerySourceLessCallableOriginDocument.JAVA_SYNTHETIC_PROPERTY
        SourceLessCallableOrigin.PROPERTY_BACKING_FIELD -> QuerySourceLessCallableOriginDocument.PROPERTY_BACKING_FIELD
        SourceLessCallableOrigin.PLUGIN -> QuerySourceLessCallableOriginDocument.PLUGIN
        SourceLessCallableOrigin.JS_DYNAMIC -> QuerySourceLessCallableOriginDocument.JS_DYNAMIC
        SourceLessCallableOrigin.NATIVE_FORWARD_DECLARATION ->
            QuerySourceLessCallableOriginDocument.NATIVE_FORWARD_DECLARATION
    }

private fun SourceLessCallableModuleKind.protocolDocument(): QuerySourceLessCallableModuleKindDocument =
    when (this) {
        SourceLessCallableModuleKind.BUILTINS -> QuerySourceLessCallableModuleKindDocument.BUILTINS
        SourceLessCallableModuleKind.LIBRARY -> QuerySourceLessCallableModuleKindDocument.LIBRARY
    }

private fun SourceLessCallableDisposition.protocolDocument(): QuerySourceLessCallableDispositionDocument =
    when (this) {
        SourceLessCallableDisposition.BUILTIN_BOUNDARY -> QuerySourceLessCallableDispositionDocument.BUILTIN_BOUNDARY
        SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED ->
            QuerySourceLessCallableDispositionDocument.LIBRARY_POLICY_EXCLUDED
        SourceLessCallableDisposition.LIBRARY_SOURCE_UNAVAILABLE ->
            QuerySourceLessCallableDispositionDocument.LIBRARY_SOURCE_UNAVAILABLE
    }

private fun <V, F> Refinement<V, F>.callableValue(): V? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

internal fun io.github.amichne.kast.traversal.contract.TraversalCallableObservation.projectCallableObservation(
    authority: QueryReferenceAuthority
): QueryWalkCallableObservationDocument? {
    val subject =
        when (val issued = authority.issueEndpoint(entry.node.endpoint)) {
            is RelationEndpointIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
            is RelationEndpointIssuance.Rejected -> return null
        }
    return QueryWalkCallableObservationDocument(
        subject,
        TraversalDepthDocument.parse(entry.depth.value).callableValue() ?: return null,
        observation.projectCallableObservation(authority) ?: return null,
        observation.requestedDomain.requestedDomainDocument(),
        relationDomainDocument(
            observation.requestedDomain.effectiveScope(entry.node.endpoint),
            observation.requestedDomain.effectiveConstraints(entry.node.endpoint),
        ) ?: return null,
        QueryRelationDomainFingerprint.parse(observation.effectiveDomain.value).callableValue() ?: return null,
    )
}
