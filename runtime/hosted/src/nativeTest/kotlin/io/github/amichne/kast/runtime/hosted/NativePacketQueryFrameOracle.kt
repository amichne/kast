package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowReadPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationCoverageDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOwnershipDocument
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument

/** Exact source declarations and fixture ownership determine expectations before native flow runs. */
internal class NativePacketQueryFrameOracle(
    val enclosing: ImpactDeclarationReferenceDocument,
    val invocation: ImpactInvocationReferenceDocument,
    val callee: ImpactExecutionReferenceDocument,
    private val ownership: ImpactSourceOwnershipDocument?,
) {
    fun matches(frame: ImpactCallFrameDocument): Boolean {
        if (
            frame.enclosing != enclosing ||
                frame.invocation != ImpactCallOccurrenceDocument.Declared(invocation) ||
                frame.callee != callee
        )
            return false
        return when (val target = frame.target) {
            ImpactCallTargetDocument.Direct -> ownership == null
            is ImpactCallTargetDocument.Implementation -> matchesImplementation(target)
        }
    }

    private fun matchesImplementation(target: ImpactCallTargetDocument.Implementation): Boolean {
        if (ownership == null || target.ownership != ownership) return false
        return matchesRelation(target) && matchesDispatch(target)
    }

    private fun matchesRelation(target: ImpactCallTargetDocument.Implementation): Boolean {
        val relation = target.relation
        if (relation.subject.declaration != invocation.callable || relation.target.declaration != invocation.callable)
            return false
        if (relation.source.declaration != callee.enclosing || relation.authority != invocation.callable.basis)
            return false
        return relation.provenance == RelationProvenanceDocument.K2_AUTHORED_SOURCE &&
            relation.coverage == RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED
    }

    private fun matchesDispatch(target: ImpactCallTargetDocument.Implementation): Boolean {
        val dispatch = target.dispatch
        if (dispatch.facts.values != listOf(target.relation) || dispatch.question.subject != invocation.callable)
            return false
        if (
            dispatch.question.meaning != RelationKindDocument.IMPLEMENTATIONS ||
                dispatch.question.requestedDomain != QueryRelationRequestedDomainDocument.SOURCE_DOMAIN
        )
            return false
        if (
            dispatch.question.position != ImpactFlowReadPositionDocument.Start ||
                !matchesDomain(dispatch.question.domain)
        )
            return false
        val coverage = dispatch.coverage as? ImpactImplementationCoverageDocument.Exhausted ?: return false
        return coverage.exactCount.value == 1L &&
            dispatch.omissions.values.isEmpty() &&
            dispatch.scopeExclusions.values.isEmpty()
    }

    private fun matchesDomain(domain: QueryRelationDomainDocument): Boolean {
        if (domain.packageName != null || domain.declarationKinds.values.isNotEmpty()) return false
        if (
            domain.scope != QuerySemanticScopeDocument.Workspace ||
                domain.sourcePolicy != QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY
        )
            return false
        if (
            domain.generatedSources != QueryDiscoveryInclusionPolicyDocument.EXCLUDE ||
                domain.libraries != QueryDiscoveryInclusionPolicyDocument.EXCLUDE
        )
            return false
        val sets = QueryDiscoverySourceSetsDocument.Exact(packetQueryList(listOf(packetQueryText("main"))))
        val directory = QueryDirectoryScopeDocument(packetQueryText("src"), QueryContainmentDocument.DESCENDANTS)
        return domain.sourceSets == sets && domain.directory == directory
    }
}
