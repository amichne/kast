package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Serialized callback evidence must belong to the enclosing walk before it crosses the wire boundary. */
class QueryWalkCallbackAdmissionTest {
    private val fixture = QueryCallbackWireFixture()
    private val callback = fixture.callbackDocument()

    private fun original(): QueryWalkObservationWireDocument =
        QueryWalkObservationDocument(
                QueryReferenceDocument.ExactSymbol(fixture.text("exact:seed")),
                callback.relation,
                ProtocolCount.parse(1).value(),
                QueryExpandedFrontierDocument.parse(1).value(),
                TraversalProgressDocument(1, 1, 0, 0),
                TraversalStrategyDocument.BreadthFirst,
                BoundedProtocolList.create(emptyList<TraversalPartialExpansionDocument>()).value(),
                QueryWalkCoverageDocument.Complete,
                callback.requestedDomain,
                callback.effectiveDomain,
                callback.domainFingerprint,
                callbackObservations =
                    BoundedProtocolList.create(
                            listOf(
                                QueryWalkCallbackObservationDocument(
                                    QueryReferenceDocument.ExactSymbol(fixture.text("exact:seed")),
                                    TraversalDepthDocument.parse(0).value(),
                                    callback,
                                )
                            )
                        )
                        .value(),
            )
            .toWireDocument()

    private fun decode(wire: QueryWalkObservationWireDocument): WireDocumentConversion<QueryWalkObservationDocument> {
        val raw = wireJson.encodeToString(QueryWalkObservationWireDocument.serializer(), wire)
        return wireJson.decodeFromString(QueryWalkObservationWireDocument.serializer(), raw).toContract()
    }

    @Test
    fun `callback walks preserve evidence in both directions and every expansion policy`() {
        for (relation in listOf(RelationKindDocument.CALLERS, RelationKindDocument.CALLEES)) {
            for (domain in QueryRelationRequestedDomainDocument.entries) {
                val wire = original().copy(relation = relation.toWireDocument(), requestedDomain = domain)
                val entry = wire.callbackObservations.single()
                val admitted =
                    wire.copy(
                        callbackObservations =
                            listOf(
                                entry.copy(
                                    observation = entry.observation.copy(relation = relation, requestedDomain = domain)
                                )
                            )
                    )
                val decoded = decode(admitted).value()
                assertEquals(relation, decoded.relation)
                assertEquals(domain, decoded.requestedDomain)
                val retained = decoded.callbackObservations.values.single()
                assertEquals(relation, retained.observation.relation)
                assertEquals(domain, retained.observation.requestedDomain)
                assertEquals(callback.flow, retained.observation.flow)
                assertEquals(callback.occurrence, retained.observation.occurrence)
            }
        }
    }

    @Test
    fun `callback for callees is rejected under callers walk`() {
        assertEquals(
            WireDocumentConversion.Rejected,
            decode(original().copy(relation = RelationKindWireDocument.CALLERS)),
        )
    }

    @Test
    fun `callback for workspace is rejected under retained seed walk`() {
        for (domain in
            listOf(
                QueryRelationRequestedDomainDocument.RETAINED_SEED,
                QueryRelationRequestedDomainDocument.SOURCE_DOMAIN,
            )) {
            assertEquals(WireDocumentConversion.Rejected, decode(original().copy(requestedDomain = domain)))
        }
    }

    @Test
    fun `callback for different effective domain is rejected at seed and deeper frontier`() {
        val wire =
            original()
                .copy(
                    effectiveDomain =
                        callback.effectiveDomain.copy(
                            scope = QuerySemanticScopeDocument.ExactFile(fixture.text("Seed.kt"))
                        )
                )
        assertEquals(WireDocumentConversion.Rejected, decode(wire))
        val entry =
            wire.callbackObservations
                .single()
                .copy(
                    subject = QueryReferenceWireDocument.ExactSymbol("exact:frontier"),
                    depth = 1,
                )
        assertEquals(
            WireDocumentConversion.Rejected,
            decode(
                wire.copy(
                    maximumDepth = 2,
                    callbackObservations = listOf(entry),
                )
            ),
        )
    }

    @Test
    fun `callback for different fingerprint is rejected`() {
        assertEquals(
            WireDocumentConversion.Rejected,
            decode(original().copy(domainFingerprint = QueryRelationDomainFingerprint.parse("2".repeat(64)).value())),
        )
    }

    @Test
    fun `callback depth identifies an expanded frontier strictly below the maximum`() {
        val wire = original()
        for (invalidDepth in listOf(-1, 1, 2)) {
            assertEquals(
                WireDocumentConversion.Rejected,
                decode(
                    wire.copy(
                        callbackObservations = listOf(wire.callbackObservations.single().copy(depth = invalidDepth))
                    )
                ),
            )
        }
        assertEquals(WireDocumentConversion.Rejected, decode(wire.copy(maximumDepth = 0)))
    }

    @Test
    fun `depth zero callback must identify the seed`() {
        val wire = original()
        val other =
            wire.callbackObservations.single().copy(subject = QueryReferenceWireDocument.ExactSymbol("exact:other"))
        assertEquals(
            WireDocumentConversion.Rejected,
            decode(wire.copy(callbackObservations = listOf(other))),
        )
    }

    @Test
    fun `deeper callback keeps its frontier subject and independent selector fingerprint`() {
        val wire = original()
        val entry = wire.callbackObservations.single()
        val fingerprint = QueryRelationDomainFingerprint.parse("2".repeat(64)).value()
        val deeper =
            entry.copy(
                subject = QueryReferenceWireDocument.ExactSymbol("exact:frontier"),
                depth = 1,
                observation = entry.observation.copy(domainFingerprint = fingerprint),
            )
        val decoded = decode(wire.copy(maximumDepth = 2, callbackObservations = listOf(deeper))).value()
        val retained = decoded.callbackObservations.values.single()
        assertEquals("exact:frontier", retained.subject.token.value)
        assertEquals(1, retained.depth.value)
        assertEquals(fingerprint, retained.observation.domainFingerprint)
        assertEquals(callback.flow, retained.observation.flow)
    }

    @Test
    fun `deeper frontier may share the seed selector scope fingerprint`() {
        val wire = original()
        val entry = wire.callbackObservations.single()
        val deeper = entry.copy(subject = QueryReferenceWireDocument.ExactSymbol("exact:frontier"), depth = 1)
        val decoded = decode(wire.copy(maximumDepth = 2, callbackObservations = listOf(deeper))).value()
        val retained = decoded.callbackObservations.values.single()
        assertEquals("exact:frontier", retained.subject.token.value)
        assertEquals(callback.domainFingerprint, retained.observation.domainFingerprint)
    }

    @Test
    fun `distinct repeated occurrences survive separate output pages without deduplication`() {
        val wire = original()
        val first = wire.callbackObservations.single()
        val repeated =
            first.copy(
                observation =
                    first.observation.copy(
                        occurrence =
                            first.observation.occurrence.copy(
                                candidateSelector = "candidate:Seed.kt:9:12",
                                range = SourceRangeWireDocument(9, 12),
                            )
                    )
            )
        val together = decode(wire.copy(callbackObservations = listOf(first, repeated))).value()
        val pages =
            listOf(first, repeated).flatMap { entry ->
                decode(wire.copy(callbackObservations = listOf(entry))).value().callbackObservations.values
            }
        assertEquals(together.callbackObservations.values, pages)
        assertEquals(listOf(5, 9), pages.map { it.observation.occurrence.range.startInclusive.value })
        assertEquals(listOf(callback.flow, callback.flow), pages.map { it.observation.flow })
    }

    @Test
    fun `empty callback evidence retains the existing empty walk contract`() {
        assertTrue(decode(original().copy(callbackObservations = emptyList())) is WireDocumentConversion.Converted)
    }

    @Test
    fun `callback seed token is rejected at positive depth under callees`() {
        rejectSeedAtPositiveDepth(RelationKindDocument.CALLEES)
    }

    @Test
    fun `callback seed token is rejected at positive depth under callers`() {
        rejectSeedAtPositiveDepth(RelationKindDocument.CALLERS)
    }

    private fun rejectSeedAtPositiveDepth(relation: RelationKindDocument) {
        val wire = original().copy(relation = relation.toWireDocument(), maximumDepth = 2)
        val seedAtOne =
            wire.callbackObservations
                .single()
                .copy(
                    depth = 1,
                    observation = wire.callbackObservations.single().observation.copy(relation = relation),
                )
        val actual = decode(wire.copy(callbackObservations = listOf(seedAtOne)))
        assertEquals(WireDocumentConversion.Rejected, actual)
    }

    private fun <V, F> Refinement<V, F>.value(): V = (this as Refinement.Refined).value

    private fun <V> WireDocumentConversion<V>.value(): V = (this as WireDocumentConversion.Converted).value
}
