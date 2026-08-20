package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RelationWorkOffset
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal class TraversalTestFixture {
    val lease: SemanticReadLease = SemanticReadLease(
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
        EvidenceGeneration.parse(31L).refined(),
    )
    val scope: SymbolSearchScope = SymbolSearchScope.Workspace(
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.INCLUDE,
        SymbolLibraryPolicy.EXCLUDE,
    )

    fun selector(
        name: String,
        offset: Int,
    ): SymbolSelector {
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(lease, scope),
            SymbolNameDiscoveryKind.SYMBOL,
            SymbolDiscoveryPattern.parse(name).refined(),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(8L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            name,
            lease,
            Path.of("/workspace/src/$name.kt"),
            "file:///workspace/src/$name.kt",
            offset,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            request,
            listOf(candidate),
            SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refined(),
            SymbolDiscoveryWorkCount.parse(1L).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
        val selection = SymbolDiscoverySelection.select(batch, 0).refined()
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            location.file,
            location.offset.value,
            location.offset.value + name.length + 1,
            name,
            "sample.$name",
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolIdentity.parse("function|sample.$name|-|||-|0").refined(),
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    fun relationBudget(
        records: Int = 2,
        bytes: Long = 100_000L,
        work: Long = 10L,
        time: Long = 10L,
    ): RelationBudget = RelationBudget(
        ResourceBudget(
            ResultLimit.parse(records).refined(),
            WorkUnitLimit.parse(work).refined(),
            ElapsedTimeLimitMillis.parse(time).refined(),
        ),
        RelationByteLimit.parse(bytes).refined(),
    )

    fun plan(
        start: SymbolSelector,
        aggregateRecords: Int = 50,
        aggregateBytes: Long = 1_000_000L,
        aggregateWork: Long = 1_000L,
        aggregateTime: Long = 1_000L,
        depth: Int = 8,
        frontier: Int = 50,
        oneHop: RelationBudget = relationBudget(),
    ): TraversalPlan = TraversalPlan.start(
        start,
        RelationMeaning.Callees,
        TraversalBudget(
            ResultLimit.parse(aggregateRecords).refined(),
            TraversalByteLimit.parse(aggregateBytes).refined(),
            WorkUnitLimit.parse(aggregateWork).refined(),
            ElapsedTimeLimitMillis.parse(aggregateTime).refined(),
            TraversalDepthLimit.parse(depth).refined(),
            TraversalFrontierLimit.parse(frontier).refined(),
            oneHop,
        ),
    ).refined()

    fun completeRead(
        request: OneHopRelationRequest,
        targets: List<SymbolSelector>,
        elapsedMillis: Long = 1L,
    ): OneHopRelationRead {
        val relationRequest = request.relationRequest()
        val facts = targets.map { target -> fact(relationRequest, endpoint(relationRequest.subject, target)) }
            .sorted()
        val batch = batch(relationRequest, facts)
        val complete = RelationCompilation.complete(batch)
        return OneHopRelationRead.Completed(
            RelationReadResult.Complete(batch, complete.coverage),
            OneHopElapsedMillis.parse(elapsedMillis).refined(),
        )
    }

    fun qualifiedRead(
        request: OneHopRelationRequest,
        targets: List<SymbolSelector>,
        limitation: RelationLimitation = RelationLimitation.PROVIDER_INCOMPLETE,
    ): OneHopRelationRead {
        val relationRequest = request.relationRequest()
        val facts = targets.map { target -> fact(relationRequest, endpoint(relationRequest.subject, target)) }
            .sorted()
        val batch = batch(relationRequest, facts)
        val nextOffset = RelationWorkOffset.parse(
            relationRequest.position.workOffset.value + facts.size,
        ).refined()
        val qualified = RelationCompilation.qualified(
            batch,
            setOf(limitation),
            nextOffset,
        ).refined()
        return OneHopRelationRead.Completed(
            RelationReadResult.Qualified(batch, qualified.coverage),
            OneHopElapsedMillis.parse(1L).refined(),
        )
    }

    fun endpoint(
        subject: RelationEndpoint,
        target: SymbolSelector,
    ): RelationEndpoint.Resolved {
        val description = SymbolDescription.from(target)
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            target.file,
            target.range.startInclusive,
            target.range.endExclusive,
            target.name.value,
            "sample.${target.name.value}",
            target.kind,
            description.compilerIdentity,
        ).refined()
        return RelationEndpoint.resolve(subject.lease, subject.scope, evidence).refined()
    }

    fun completeRelationResult(
        request: RelationRequest,
        targets: List<RelationEndpoint.Resolved>,
    ): RelationReadResult.Complete {
        val facts = targets.map { target -> fact(request, target) }.sorted()
        val batch = batch(request, facts)
        val complete = RelationCompilation.complete(batch)
        return RelationReadResult.Complete(batch, complete.coverage)
    }

    fun qualifiedRelationResult(
        request: RelationRequest,
    ): RelationReadResult.Qualified {
        val batch = batch(request, emptyList())
        val qualified = RelationCompilation.qualified(
            batch,
            setOf(RelationLimitation.PROVIDER_INCOMPLETE),
            request.position.workOffset,
        ).refined()
        return RelationReadResult.Qualified(batch, qualified.coverage)
    }

    private fun OneHopRelationRequest.relationRequest(): RelationRequest =
        when (val oneHopPosition = position) {
            OneHopRelationPosition.Start -> when (val subject = node.endpoint) {
                is RelationEndpoint.Subject -> RelationRequest.start(subject.selector, meaning, budget)
                is RelationEndpoint.Resolved -> RelationRequest.start(subject, meaning, budget)
            }
            is OneHopRelationPosition.Resume -> when (val subject = node.endpoint) {
                is RelationEndpoint.Subject -> RelationRequest.resume(
                    subject.selector,
                    meaning,
                    budget,
                    oneHopPosition.continuation,
                ).refined()
                is RelationEndpoint.Resolved -> RelationRequest.resume(
                    subject,
                    meaning,
                    budget,
                    oneHopPosition.continuation,
                ).refined()
            }
        }

    private fun fact(
        request: RelationRequest,
        endpoint: RelationEndpoint.Resolved,
    ): RelationFact {
        val occurrence = RelationOccurrence.fromBoundary(
            request.subject.file,
            request.subject.range.startInclusive,
            request.subject.range.startInclusive + 1,
        ).refined()
        val (source, target) = if (request.meaning == RelationMeaning.Callees) {
            request.subject to endpoint
        } else {
            endpoint to request.subject
        }
        return RelationFact.create(
            request,
            source,
            target,
            occurrence,
            RelationProvenance.K2_AUTHORED_SOURCE,
        ).refined()
    }

    private fun batch(
        request: RelationRequest,
        facts: List<RelationFact>,
    ): RelationBatch {
        val bytes = facts.sumOf { fact ->
            fact.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
        }
        return RelationBatch.create(
            request,
            facts,
            RelationByteCount.parse(bytes).refined(),
            RelationWorkCount.parse(facts.size.toLong()).refined(),
        ).refined()
    }
}

internal class InMemoryRelationReader(
    graph: Map<SymbolSelector, List<SymbolSelector>>,
    private val fixture: TraversalTestFixture,
    private val qualified: Set<String> = emptySet(),
) : OneHopRelationReader {
    private val nodes = graph.entries.associate { (selector, targets) ->
        selector.fingerprint.value to (selector to targets)
    }
    val requests = mutableListOf<OneHopRelationRequest>()

    override suspend fun read(request: OneHopRelationRequest): OneHopRelationRead {
        requests += request
        val (_, targets) = nodes.getValue(request.node.fingerprint.value)
        return if (request.node.fingerprint.value in qualified) {
            fixture.qualifiedRead(request, targets)
        } else {
            fixture.completeRead(request, targets)
        }
    }
}

internal fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error(failure.toString())
}
