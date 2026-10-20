package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowDomainDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowReadPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationCoverageDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationDispatchDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationExclusionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOwnershipDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryScopeMembershipAuthorityDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import junit.framework.TestCase.assertTrue

/** Detached frame assertions compare source ownership and target-set evidence, not runtime injection. */
internal class NativePacketT1DispatchOracle(private val facts: NativePacketT1QueryFacts) {
    fun assertProductionKnownEchoExclusion(paths: List<ImpactPathDocument>) {
        assertTrue("T1 needs the original S1 constructor", paths.any { it.producer == facts.s1.origin })
        val dispatches = dispatches(paths)
        assertTrue("T1 production lacks actual implementation dispatch evidence", dispatches.isNotEmpty())
        val matching = dispatches.filter { matchesDispatch(it, NativePacketCompositeQueryPolicy.PRODUCTION) }
        assertTrue("T1 production lacks the exact source-domain dispatch", matching.isNotEmpty())
        val excluded = matching.flatMap { it.scopeExclusions.values }.any(::exactEchoExclusion)
        assertTrue(
            "T1 needs the exact Echo method and typed SOURCE_DOMAIN exclusion, not an unmatched target",
            excluded,
        )
    }

    fun assertProductionWithoutKnownDeclaration(paths: List<ImpactPathDocument>) {
        assertTrue("T1 needs the original S1 constructor", paths.any { it.producer == facts.s1.origin })
        val matching = dispatches(paths).filter { matchesDispatch(it, NativePacketCompositeQueryPolicy.PRODUCTION) }
        assertTrue("The unchanged request needs actual production-domain dispatch evidence", matching.isNotEmpty())
        assertTrue(
            "The production-only request must not execute the actual Echo implementation",
            frames(paths).none { it.callee.enclosing == facts.echo },
        )
    }

    private fun exactEchoExclusion(exclusion: ImpactImplementationExclusionDocument) =
        exclusion.target.declaration == facts.echo &&
            exclusion.target.name == packetQueryText("readOffers") &&
            exclusion.target.kind == SymbolKindDocument.FUNCTION &&
            exclusion.basis == facts.echo.basis &&
            exclusion.reason == QueryScopeExclusionReasonDocument.SOURCE_DOMAIN &&
            exclusion.membershipAuthority == QueryScopeMembershipAuthorityDocument.IMPORTED_MODEL_NATIVE_SCOPE &&
            exclusion.occurrence.file == facts.echo.file &&
            exclusion.occurrence.range == facts.echo.range

    fun assertCombinedTargets(paths: List<ImpactPathDocument>) {
        assertTrue("T1 needs the original S1 constructor", paths.any { it.producer == facts.s1.origin })
        val frames = frames(paths)
        assertTrue(
            "T1 combined lacks the production target",
            frames.any { matches(it, facts.mainOwnership, NativePacketCompositeQueryPolicy.COMBINED) },
        )
        assertTrue(
            "T1 combined lacks the actual test target",
            frames.any { matches(it, facts.testOwnership, NativePacketCompositeQueryPolicy.COMBINED) },
        )
    }

    fun matches(
        frame: ImpactCallFrameDocument,
        ownership: ImpactSourceOwnershipDocument,
        policy: NativePacketCompositeQueryPolicy,
    ): Boolean {
        val target = frame.target as? ImpactCallTargetDocument.Implementation ?: return false
        if (frame.enclosing != facts.s1.origin.enclosing || frame.callee.enclosing != ownership.declaration)
            return false
        if (frame.callee.executionOwner != ImpactExecutionOwnerDocument.Declaration) return false
        val call = ImpactCallOccurrenceDocument.Declared(facts.s1.common.callerFrame.invocation)
        if (frame.invocation != call || target.ownership != ownership) return false
        val fact = target.relation
        val exact =
            fact.source.declaration == ownership.declaration &&
                fact.subject.declaration == facts.s1.common.callerFrame.invocation.callable &&
                fact.target.declaration == facts.s1.common.callerFrame.invocation.callable
        return exact && fact in target.dispatch.facts.values && matchesDispatch(target.dispatch, policy)
    }

    fun frames(paths: List<ImpactPathDocument>) =
        paths
            .flatMap { it.steps.values }
            .filterIsInstance<ImpactPathStepDocument.Compiler>()
            .mapNotNull { step ->
                when (val operation = step.transfer.compositeOperation()) {
                    is ImpactSourceOperationDocument.Enter -> operation.frame
                    is ImpactSourceOperationDocument.Return -> operation.frame
                    is ImpactSourceOperationDocument.SeedReturn -> operation.frame
                    else -> null
                }
            }

    private fun matchesDispatch(
        dispatch: ImpactImplementationDispatchDocument,
        policy: NativePacketCompositeQueryPolicy,
    ): Boolean {
        val expected =
            if (policy == NativePacketCompositeQueryPolicy.COMBINED) setOf(facts.s1.common.clientMethod, facts.echo)
            else setOf(facts.s1.common.clientMethod)
        if (!exactQuestion(dispatch.question) || !exactDomain(dispatch.question.domain, policy)) return false
        if (policy == NativePacketCompositeQueryPolicy.COMBINED && dispatch.scopeExclusions.values.isNotEmpty())
            return false
        return exactTargets(dispatch, expected)
    }

    private fun exactQuestion(question: ImpactFlowDomainDocument) =
        question.subject == facts.s1.common.callerFrame.invocation.callable &&
            question.meaning == RelationKindDocument.IMPLEMENTATIONS &&
            question.requestedDomain == QueryRelationRequestedDomainDocument.SOURCE_DOMAIN &&
            question.position == ImpactFlowReadPositionDocument.Start

    private fun exactDomain(domain: QueryRelationDomainDocument, policy: NativePacketCompositeQueryPolicy) =
        domain.scope == QuerySemanticScopeDocument.Workspace &&
            domain.directory == policy.domain.directory &&
            domain.sourcePolicy == policy.sourcePolicy &&
            domain.sourceSets == QueryDiscoverySourceSetsDocument.Exact(packetQueryList(policy.sourceSets)) &&
            domain.generatedSources == QueryDiscoveryInclusionPolicyDocument.EXCLUDE &&
            domain.libraries == QueryDiscoveryInclusionPolicyDocument.EXCLUDE

    private fun exactTargets(
        dispatch: ImpactImplementationDispatchDocument,
        expected: Set<io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument>,
    ): Boolean {
        val coverage = dispatch.coverage as? ImpactImplementationCoverageDocument.Exhausted ?: return false
        if (coverage.exactCount.value != expected.size.toLong() || dispatch.omissions.values.isNotEmpty()) return false
        return dispatch.facts.values.size == expected.size &&
            dispatch.facts.values.map { it.source.declaration }.toSet() == expected &&
            dispatch.facts.values.all { fact ->
                fact.authority == facts.echo.basis &&
                    fact.provenance == RelationProvenanceDocument.K2_AUTHORED_SOURCE &&
                    fact.coverage == RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED
            }
    }

    private fun dispatches(paths: List<ImpactPathDocument>): List<ImpactImplementationDispatchDocument> {
        val frames = frames(paths).mapNotNull { (it.target as? ImpactCallTargetDocument.Implementation)?.dispatch }
        val terminals = paths.mapNotNull { path ->
            val terminal = path.terminal as? ImpactPathTerminalDocument.UnresolvedFlow
            (terminal?.evidence as? ImpactFlowObligationEvidenceDocument.ImplementationDispatch)?.dispatch
        }
        return frames + terminals
    }
}
