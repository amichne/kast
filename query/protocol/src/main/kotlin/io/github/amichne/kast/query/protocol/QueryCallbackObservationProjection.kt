package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodySupplyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackCallableDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackForwardingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterIdentityDocument
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryWalkCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.query.contract.QueryRelationQuestion
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackBodySupply
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.traversal.contract.TraversalCallbackObservation
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal fun RelationCallbackObservation.projectCallbackObservation(
    authority: QueryReferenceAuthority,
    question: QueryRelationQuestion,
): QueryCallbackObservationDocument? {
    return projectCallbackObservation(authority, question.effectiveScope, question.effectiveConstraints)
}

private fun RelationCallbackObservation.projectCallbackObservation(
    authority: QueryReferenceAuthority,
    scope: io.github.amichne.kast.symbol.contract.SymbolSearchScope,
    constraints: io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints,
): QueryCallbackObservationDocument? {
    val projection = CallbackProjection(authority, basis)
    return QueryCallbackObservationDocument.create(
            projection.occurrence(occurrence) ?: return null,
            projection.callable(target) ?: return null,
            projection.callable(lexicalOwner) ?: return null,
            projection.occurrence(callbackBody) ?: return null,
            when (val policy = policy) {
                is CallbackNamedCallPolicy.Unavailable ->
                    QueryCallbackNamedPolicyDocument.Unavailable(policy.cause.protocolCallbackDocument())
                CallbackNamedCallPolicy.AdmittedInline -> QueryCallbackNamedPolicyDocument.AdmittedInline
                CallbackNamedCallPolicy.AdmittedDirect -> QueryCallbackNamedPolicyDocument.AdmittedDirect
                is CallbackNamedCallPolicy.Excluded ->
                    QueryCallbackNamedPolicyDocument.Excluded(
                        policy.reason.protocolCallbackDocument(),
                        projection.occurrence(policy.boundary) ?: return null,
                    )
            },
            projection.flow(flow) ?: return null,
            meaning.protocolDocument(),
            requestedDomain.requestedDomainDocument(),
            relationDomainDocument(scope, constraints) ?: return null,
            QueryRelationDomainFingerprint.parse(effectiveDomain.value).callbackValue() ?: return null,
        )
        .callbackValue()
}

internal fun TraversalCallbackObservation.projectCallbackObservation(
    authority: QueryReferenceAuthority
): QueryWalkCallbackObservationDocument? {
    val subject =
        when (val issued = authority.issueEndpoint(entry.node.endpoint)) {
            is RelationEndpointIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
            is RelationEndpointIssuance.Rejected -> return null
        }
    return QueryWalkCallbackObservationDocument(
        subject,
        TraversalDepthDocument.parse(entry.depth.value).callbackValue() ?: return null,
        observation.projectCallbackObservation(
            authority,
            observation.requestedDomain.effectiveScope(entry.node.endpoint),
            observation.requestedDomain.effectiveConstraints(entry.node.endpoint),
        ) ?: return null,
    )
}

internal class CallbackProjection(val authority: QueryReferenceAuthority, val basis: SemanticReadAuthority) {
    fun occurrence(value: RelationOccurrence): RelationOccurrenceDocument? {
        val issued =
            when (
                val issued =
                    authority.issueRangeCandidate(
                        basis,
                        value.file,
                        value.range.startInclusive,
                        value.range.endExclusive,
                    )
            ) {
                is CandidateSelectorTokenIssuance.Issued -> issued.selector
                is CandidateSelectorTokenIssuance.Rejected -> return null
            }
        return RelationOccurrenceDocument(
            issued,
            ProtocolText.parse(value.file.stableValue).callbackValue() ?: return null,
            SourceRangeDocument.create(
                    ProtocolOffset.parse(value.range.startInclusive).callbackValue() ?: return null,
                    ProtocolOffset.parse(value.range.endExclusive).callbackValue() ?: return null,
                )
                .callbackValue() ?: return null,
        )
    }

    fun callable(value: CompilerGroundedSymbolEvidence): QueryCallbackCallableDocument? =
        callable(value.file, value.range, value.name.value, value.kind, value.compilerIdentity.value, value.signature)

    fun callable(value: RelationEndpoint): QueryCallbackCallableDocument? =
        callable(value.file, value.range, value.name.value, value.kind, value.compilerIdentity.value, value.signature)

    private fun callable(
        file: io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity,
        range: io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange,
        name: String,
        kind: io.github.amichne.kast.symbol.contract.CompilerSymbolKind,
        identity: String,
        signature: io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature,
    ): QueryCallbackCallableDocument? {
        val compiler =
            CompilerSymbolEvidenceDocument.restore(
                    ProtocolText.parse(identity).callbackValue() ?: return null,
                    signature.protocolDocument() ?: return null,
                )
                .callbackValue() ?: return null
        val declaration =
            occurrence(
                RelationOccurrence.fromBoundary(file, range.startInclusive, range.endExclusive).callbackValue()
                    ?: return null
            ) ?: return null
        val target =
            QueryExcludedCompilerTargetDocument.create(
                    declaration.file,
                    declaration.range,
                    ProtocolText.parse(name).callbackValue() ?: return null,
                    kind.protocolKind(),
                    compiler,
                )
                .callbackValue() ?: return null
        return QueryCallbackCallableDocument(declaration, target)
    }

    fun anonymous(value: RelationCallableBody.Anonymous): QueryCallbackBodyDocument.Anonymous? {
        return QueryCallbackBodyDocument.Anonymous(
            occurrence(
                RelationOccurrence.fromBoundary(value.file, value.range.startInclusive, value.range.endExclusive)
                    .callbackValue() ?: return null
            ) ?: return null,
            CompilerSymbolEvidenceDocument.restore(
                    ProtocolText.parse(value.compilerIdentity.value).callbackValue() ?: return null,
                    value.signature.protocolDocument() ?: return null,
                )
                .callbackValue() ?: return null,
        )
    }

    fun body(value: RelationCallableBody): QueryCallbackBodyDocument? =
        when (value) {
            is RelationCallableBody.Named -> callable(value.evidence)?.let(QueryCallbackBodyDocument::Named)
            is RelationCallableBody.Anonymous -> anonymous(value)
        }

    fun flow(value: CallbackInvocationFlowRead): QueryCallbackFlowDocument? =
        when (value) {
            is CallbackInvocationFlowRead.Unavailable ->
                QueryCallbackFlowDocument.Unavailable(value.cause.protocolCallbackDocument())
            is CallbackInvocationFlowRead.ContractRejected ->
                QueryCallbackFlowDocument.ContractRejected(value.cause.protocolCallbackDocument())
            is CallbackInvocationFlowRead.Observed -> observedFlow(value.flow)
        }

    private fun observedFlow(flow: CallbackInvocationFlow): QueryCallbackFlowDocument? {
        return QueryCallbackFlowDocument.Observed(
            flow.basis.impactDocument().callbackValue() ?: return null,
            anonymous(flow.body) ?: return null,
            binding(flow.binding) ?: return null,
            BoundedProtocolList.create(flow.invocations.map { invocation(it) ?: return null }).callbackValue()
                ?: return null,
            BoundedProtocolList.create(
                    flow.obligations
                        .sortedBy { it.ordinal }
                        .map {
                            it.protocolCallbackDocument()
                        }
                )
                .callbackValue() ?: return null,
            BoundedProtocolList.create(flow.ownerBindings.map { ownerBinding(it) ?: return null }).callbackValue()
                ?: return null,
            flow.scan.protocolCallbackDocument(),
        )
    }

    private fun ownerBinding(value: CallbackBodyBinding): QueryCallbackBodyBindingDocument? {
        return QueryCallbackBodyBindingDocument(
            anonymous(value.body) ?: return null,
            when (val supply = value.supply) {
                is CallbackBodySupply.Invocation ->
                    QueryCallbackBodySupplyDocument.Invocation(occurrence(supply.occurrence) ?: return null)
                CallbackBodySupply.Stored -> QueryCallbackBodySupplyDocument.Stored
                CallbackBodySupply.Unsupported -> QueryCallbackBodySupplyDocument.Unsupported
                is CallbackBodySupply.DefaultParameter ->
                    QueryCallbackBodySupplyDocument.DefaultParameter(occurrence(supply.parameter) ?: return null)
                is CallbackBodySupply.DirectInvocation ->
                    QueryCallbackBodySupplyDocument.DirectInvocation(occurrence(supply.occurrence) ?: return null)
                is CallbackBodySupply.Returned ->
                    QueryCallbackBodySupplyDocument.Returned(occurrence(supply.occurrence) ?: return null)
            },
            binding(value.binding) ?: return null,
            BoundedProtocolList.create(value.obligations.sortedBy { it.ordinal }.map { it.protocolCallbackDocument() })
                .callbackValue() ?: return null,
        )
    }

    private fun invocation(value: CallbackParameterInvocation): QueryCallbackInvocationDocument? {
        return QueryCallbackInvocationDocument(
            occurrence(value.occurrence) ?: return null,
            body(value.owner) ?: return null,
            BoundedProtocolList.create(
                    value.callableTransfers.map {
                        it.impactDocument().callbackValue() ?: return null
                    }
                )
                .callbackValue() ?: return null,
            BoundedProtocolList.create(
                    value.forwardings.map {
                        QueryCallbackForwardingDocument(
                            parameter(it.source) ?: return null,
                            occurrence(it.argument) ?: return null,
                            bound(it.target) ?: return null,
                        )
                    }
                )
                .callbackValue() ?: return null,
        )
    }

    fun parameter(value: CallbackParameterIdentity): QueryCallbackParameterIdentityDocument? {
        return QueryCallbackParameterIdentityDocument(
            callable(value.callable) ?: return null,
            ProtocolOffset.parse(value.position.value).callbackValue() ?: return null,
            occurrence(value.parameter) ?: return null,
        )
    }

    private fun binding(value: CallbackBindingEvidence): QueryCallbackBindingDocument? {
        return when (value) {
            is CallbackBindingEvidence.Unavailable ->
                QueryCallbackBindingDocument.Unavailable(value.cause.protocolCallbackDocument())
            is CallbackBindingEvidence.Bound -> bound(value.binding)
            is CallbackBindingEvidence.Default ->
                QueryCallbackBindingDocument.Default(
                    parameter(value.binding.parameter) ?: return null,
                    occurrence(value.binding.defaultValue) ?: return null,
                )
            is CallbackBindingEvidence.Direct ->
                QueryCallbackBindingDocument.Direct(
                    value.binding.basis.impactDocument().callbackValue() ?: return null,
                    occurrence(value.binding.occurrence) ?: return null,
                    body(value.binding.owner) ?: return null,
                )
        }
    }

    private fun bound(value: CallbackArgumentBinding): QueryCallbackBindingDocument.Bound? {
        return QueryCallbackBindingDocument.Bound(
            value.invocation.impactDocument().callbackValue() ?: return null,
            occurrence(
                RelationOccurrence.fromBoundary(
                        value.invocation.enclosing.file,
                        value.invocation.range.startInclusive,
                        value.invocation.range.endExclusive,
                    )
                    .callbackValue() ?: return null
            ) ?: return null,
            body(value.invocationOwner) ?: return null,
            callable(value.invocation.callable) ?: return null,
            ProtocolOffset.parse(value.position.value).callbackValue() ?: return null,
            occurrence(value.parameter) ?: return null,
        )
    }
}

private fun <V, F> Refinement<V, F>.callbackValue(): V? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
