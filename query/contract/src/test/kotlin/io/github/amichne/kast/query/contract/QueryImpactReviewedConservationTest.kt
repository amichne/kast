package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.DependencyArtifactDigest
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedIterableMapArtifact
import io.github.amichne.kast.relation.contract.ReviewedIterableMapCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueField
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached evidence exercises query conservation. Native fixtures own compiler and dependency proof. */
class QueryImpactReviewedConservationTest {
    @Test
    fun `reviewed correspondence keeps its field identity and separate witness provenance`() {
        val fixture = ReviewedLedgerFixture()
        val ledger = fixture.ledger(listOf(fixture.path), fixture.observations).value()
        assertTrue(ledger.representationModels.isEmpty())
        assertTrue(ledger.boundaryModels.isEmpty())
        assertEquals(QueryImpactFlowSemantics.KOTLIN_FORWARD_V1, ledger.semantics)
        assertEquals(
            listOf(ValuePathSegment.Element, ValuePathSegment.Field(fixture.field)),
            fixture.edge.target.valuePath.segments,
        )
        assertSame(fixture.field, (fixture.edge.target.valuePath.segments.last() as ValuePathSegment.Field).field)
        val count = QueryImpactWitnessView.count(ledger, QueryImpactWitnessSection.NATIVE_READS).value()
        val page = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.NATIVE_READS, 0, count.value).value()
        assertEquals(1, page.entries.count { it.evidence is QueryImpactWitnessEntry.ReviewedTransfer })
        assertTrue(page.entries.none { it.evidence is QueryImpactWitnessEntry.CompilerTransfer })
    }

    @Test
    fun `a reviewed path needs its exact native observation`() {
        val fixture = ReviewedLedgerFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_NATIVE_OBSERVATION),
            fixture.ledger(listOf(fixture.path), listOf(fixture.observations.last())),
        )
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_REVIEWED_STEP),
            fixture.ledger(listOf(fixture.path), listOf(fixture.observe(fixture.source)) + fixture.observations.last()),
        )
    }

    @Test
    fun `a conserved prefix cannot admit a later unproven reviewed step`() {
        val fixture = ReviewedLedgerFixture()
        val emptyObservation = fixture.observe(fixture.source)
        val first =
            QueryImpactPath.fromEvidence(
                    fixture.source,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(emptyObservation).value(),
                )
                .value()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_REVIEWED_STEP),
            fixture.ledger(listOf(first, fixture.path), listOf(emptyObservation, fixture.observations.last())),
        )
    }

    @Test
    fun `an omitted reviewed branch precedes missing obligations and an unproven terminal`() {
        val fixture = ReviewedLedgerFixture()
        val obligation = ValueFlowObligation(fixture.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val observed = fixture.observe(fixture.source, listOf(fixture.edge), listOf(obligation))
        val truncated =
            QueryImpactPath.fromEvidence(
                    fixture.source,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(fixture.observe(fixture.source)).value(),
                )
                .value()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            fixture.ledger(listOf(truncated), listOf(observed, fixture.observations.last())),
        )
    }

    @Test
    fun `a reviewed step cannot discharge an unmodeled call obligation`() {
        val fixture = ReviewedLedgerFixture()
        val obligation = ValueFlowObligation(fixture.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val observed = fixture.observe(fixture.source, listOf(fixture.edge), listOf(obligation))
        val observations = listOf(observed, fixture.observations.last())
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            fixture.ledger(listOf(fixture.path), observations),
        )
        val unresolved =
            QueryImpactPath.fromEvidence(
                    fixture.source,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(obligation),
                )
                .value()
        assertTrue(
            fixture.ledger(listOf(fixture.path, unresolved), observations).value().closure
                is QueryImpactClosure.Unresolved
        )
    }

    @Test
    fun `distinct reviewed bindings cannot share prefix evidence at equal source ranges`() {
        val first = ReviewedLedgerFixture()
        val second = ReviewedLedgerFixture(occurrenceStart = 21)
        assertEquals(first.source.enclosing, second.source.enclosing)
        assertEquals(first.source.range, second.source.range)
        assertNotEquals(first.binding, second.binding)
        assertNotEquals(first.source, second.source)
        val prefixes = QueryImpactLedgerPaths(listOf(first.path, second.path))
        assertNotSame(prefixes.at(first.path, 0), prefixes.at(second.path, 0))
        assertNotSame(prefixes.at(first.path, 1), prefixes.at(second.path, 1))
        assertEquals(listOf(first.path), prefixes.at(first.path, 0).paths)
        assertEquals(listOf(second.path), prefixes.at(second.path, 0).paths)
    }

    @Test
    fun `a reviewed outgoing branch prevents premature completion and cannot disappear`() {
        val fixture = ReviewedLedgerFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactPathFailure.TERMINAL_UNPROVEN),
            QueryImpactTerminal.SupportedDomainEnd.admit(fixture.observations.first()),
        )
        val falseEnd = QueryImpactTerminal.SupportedDomainEnd.admit(fixture.observe(fixture.source)).value()
        val truncated =
            QueryImpactPath.fromEvidence(
                    producer = fixture.source,
                    steps = emptyList(),
                    representation = QueryImpactRepresentation.NotModeled,
                    terminal = falseEnd,
                )
                .value()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            fixture.ledger(listOf(truncated), fixture.observations),
        )
    }

    @Test
    fun `native admission rejects another source epoch and a mismatched transfer source`() {
        val fixture = ReviewedLedgerFixture()
        val later = ReviewedLedgerFixture(generation = 2)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH),
            fixture.question.admitObservation(later.observations.first()),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH),
            ValueFlowStep.fromEvidence(
                source = fixture.edge.target,
                transfers = emptyList(),
                obligations = emptyList(),
                terminal = ValueFlowTerminal.SupportedDomainExhausted,
                domain = fixture.domain,
                examinedWorkUnits = RelationWorkCount.parse(1).value(),
                reviewedTransfers = listOf(fixture.edge),
            ),
        )
    }

    @Test
    fun `reviewed binding charges independent target name text against capacity`() {
        val shortName = "map"
        val longName = "m".repeat(512)
        val compact = ReviewedLedgerFixture(targetName = shortName).binding
        val expanded = ReviewedLedgerFixture(targetName = longName).binding
        val compactBytes = compact.storageBytes(QueryImpactRetainedGraph())
        val expandedBytes = expanded.storageBytes(QueryImpactRetainedGraph())
        assertEquals(3L * (longName.length - shortName.length), expandedBytes - compactBytes)
        val allowance = QueryStorageAllowance.Bounded(QueryByteLimit.parse(compactBytes).value())
        assertTrue(allowance.permits(compactBytes))
        assertFalse(allowance.permits(expandedBytes))
    }

    @Test
    fun `reviewed binding charges shared evidence nodes once and every incoming reference`() {
        val binding = ReviewedLedgerFixture().binding
        val graph = QueryImpactRetainedGraph()
        assertTrue(binding.storageBytes(graph) > 8L)
        assertEquals(8L, binding.storageBytes(graph))
        assertEquals(8L, binding.target.storageBytes(graph))
        assertEquals(8L, graph.text(binding.target.name.value))
        assertEquals(8L, graph.node(binding.target.file) { 0L })
        assertEquals(8L, graph.node(binding.occurrence.file) { 0L })
        assertEquals(8L, graph.node(binding.occurrence.range) { 0L })
        assertEquals(8L, graph.collection(binding.artifact.sourceSections) { graph.node(it) { 0L } })
        for (section in binding.artifact.sourceSections) {
            assertEquals(8L, graph.node(section) { 0L })
        }
    }
}

private class ReviewedLedgerFixture(generation: Long = 1, targetName: String = "map", occurrenceStart: Int = 20) {
    private val base = QueryImpactLedgerFixture()
    private val lease = SemanticReadLease(base.lease.workspaceRoot, EvidenceGeneration.parse(generation).value())
    private val owner = RelationEndpoint.resolve(lease, base.scope, base.evidence).value()
    val domain = RelationRequest.start(owner, RelationMeaning.References, base.domain.budget, base.domain.boundary)
    private val bodyRange = range(30, 80)
    private val body =
        RelationCallableBody.Anonymous.fromCompiler(
                file = base.file,
                range = bodyRange,
                signature =
                    CanonicalCompilerSignature.function(
                            rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(base.file, bodyRange),
                            rawReceiverType = null,
                            rawContextReceiverTypes = emptyList(),
                            rawValueParameterTypes = listOf("fixture.WireValue"),
                            rawTypeParameterCount = 0,
                        )
                        .value() as CanonicalCompilerSignature.Function,
            )
            .value()
    private val target =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file =
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/collections/CollectionsKt.class")
                            .value()
                    ),
                rawStartInclusive = 1000,
                rawEndExclusive = 1100,
                rawName = targetName,
                rawQualifiedIdentity = "kotlin.collections.map",
                kind = CompilerSymbolKind.FUNCTION,
                signature =
                    CanonicalCompilerSignature.function(
                            rawQualifiedIdentity = "kotlin.collections.map",
                            rawReceiverType = "kotlin/collections/Iterable<T>",
                            rawContextReceiverTypes = emptyList(),
                            rawValueParameterTypes = listOf("(T)->R"),
                            rawTypeParameterCount = 2,
                        )
                        .value(),
            )
            .value()
    val binding =
        ReviewedCallbackValueBinding.admit(
                enclosing = owner,
                body = body,
                occurrence = RelationOccurrence.fromBoundary(base.file, occurrenceStart, 100).value(),
                supplier = RelationCallableBody.Named.fromCompiler(owner.evidence).value(),
                selected = ReviewedIterableMapCallable.admit(target, ValueArgumentPosition.parse(0).value()).value(),
                artifact =
                    ReviewedIterableMapArtifact.admit(
                            DependencyArtifactDigest.parse(
                                    "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3"
                                )
                                .value(),
                            DependencyArtifactDigest.parse(
                                    "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2"
                                )
                                .value(),
                        )
                        .value(),
            )
            .value()
    val field =
        ValueField.fromCompiler(
                RelationEndpoint.resolve(
                        lease = lease,
                        scope = base.scope,
                        evidence =
                            CompilerGroundedSymbolEvidence.fromBoundary(
                                    file = base.file,
                                    rawStartInclusive = 150,
                                    rawEndExclusive = 170,
                                    rawName = "payload",
                                    rawQualifiedIdentity = "fixture.ClientEnvelope.payload",
                                    kind = CompilerSymbolKind.PROPERTY,
                                    signature =
                                        CanonicalCompilerSignature.property(
                                                "fixture.ClientEnvelope.payload",
                                                null,
                                                emptyList(),
                                                "fixture.ClientValue",
                                            )
                                            .value(),
                                )
                                .value(),
                    )
                    .value()
            )
            .value()
    val source =
        ValueSite.fromCompiler(
                enclosing = owner,
                range = range(40, 50),
                role = ValueRole.Return,
                executionOwner = ValueExecutionOwner.reviewedCallback(binding),
                valuePath = ValueAccessPath.fromSegments(listOf(ValuePathSegment.Field(field))).value(),
            )
            .value()
    val edge = ReviewedValueTransfer.callbackResult(source, binding).value()
    val observations = listOf(observe(source, listOf(edge)), observe(edge.target))
    val path =
        QueryImpactPath.fromEvidence(
                producer = source,
                steps = listOf(QueryImpactStep.ReviewedLibrary(edge)),
                representation = QueryImpactRepresentation.NotModeled,
                terminal = QueryImpactTerminal.SupportedDomainEnd.admit(observations.last()).value(),
            )
            .value()
    private val invocation = ValueInvocation.fromCompiler(owner, range(10, 15), owner).value()
    val question =
        QueryImpactSource.admit(
                producers = listOf(QueryImpactProducer.admit(invocation.resultSite(), invocation).value()),
                representationModels = emptyList(),
                boundaryModels = emptyList(),
                domain = domain.boundary,
            )
            .value()

    fun observe(
        source: ValueSite,
        reviewed: List<ReviewedValueTransfer> = emptyList(),
        obligations: List<ValueFlowObligation> = emptyList(),
    ) =
        ValueFlowStep.fromEvidence(
                source = source,
                transfers = emptyList(),
                obligations = obligations,
                terminal =
                    if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted
                    else ValueFlowTerminal.Unresolved,
                domain = domain,
                examinedWorkUnits = RelationWorkCount.parse(1).value(),
                reviewedTransfers = reviewed,
            )
            .value()

    fun ledger(paths: List<QueryImpactPath>, observations: List<ValueFlowStep>) =
        QueryImpactLedger.fromEvidence(
            seeds = listOf(source),
            domain = domain.boundary,
            semantics = QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            representationModels = emptyList(),
            boundaryModels = emptyList(),
            observations = observations,
            paths = paths,
        )
}

private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

private fun <V, F> Refinement<V, F>.value(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture refinement rejected: $failure")
    }
