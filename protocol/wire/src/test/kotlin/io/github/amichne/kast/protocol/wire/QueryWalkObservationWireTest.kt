package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionLocationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryWalkObservationWireTest {
    @Test
    fun `query walk coverage encodes every closed state and rejects an unknown state`() {
        val cases: List<Pair<QueryWalkCoverageWireDocument, String>> =
            listOf(
                QueryWalkCoverageWireDocument.Complete to "walk-coverage-complete.json",
                QueryWalkCoverageWireDocument.Resumable(
                    listOf(TraversalLimitationWireDocument.RECORD_LIMIT_REACHED),
                    emptyList(),
                ) to "walk-coverage-resumable.json",
                QueryWalkCoverageWireDocument.TerminalIncomplete(
                    listOf(TraversalLimitationWireDocument.ONE_HOP_INCOMPLETE),
                    listOf(RelationLimitationWireDocument.UNRESOLVED_TARGET),
                ) to "walk-coverage-terminal.json",
            )
        for ((coverage, fixture) in cases) {
            assertEquals(
                wireJson.parseToJsonElement(checkNotNull(javaClass.getResource("/query/$fixture")).readText()),
                wireJson.encodeToJsonElement(QueryWalkCoverageWireDocument.serializer(), coverage),
            )
        }
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryWalkCoverageWireDocument.serializer(),
                checkNotNull(javaClass.getResource("/query/walk-coverage-unknown.json")).readText(),
            )
        }
    }

    @Test
    fun `query walk observation retains partial expansion depth evidence and remainder`() {
        for (remainder in TraversalExpansionRemainderDocument.entries) {
            val observation = partialObservation(remainder)
            val wire = observation.toWireDocument()
            val encoded = wireJson.encodeToJsonElement(QueryWalkObservationWireDocument.serializer(), wire)
            assertEquals(
                wireJson.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/query/walk-effective-domain.json")).readText()
                ),
                encoded.jsonObject.getValue("effective_domain"),
            )
            val expectedPartial =
                wireJson.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/query/walk-partial-expansion.json"))
                        .readText()
                        .replace("not_explored", remainder.name.lowercase())
                )
            assertEquals(expectedPartial, encoded.jsonObject.getValue("partial_expansions").jsonArray.single())
            assertEquals(WireDocumentConversion.Converted(observation), wire.toContract())

            val unknown = encoded.toString().replace(remainder.name.lowercase(), "unknown")
            assertThrows(SerializationException::class.java) {
                wireJson.decodeFromString(QueryWalkObservationWireDocument.serializer(), unknown)
            }
        }
    }

    @Test
    fun `complete empty walk encodes mandatory domain and rejects missing witness fields`() {
        val observation =
            QueryWalkObservationDocument(
                QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:fixture-node").value()),
                RelationKindDocument.REFERENCES,
                ProtocolCount.parse(2).value(),
                QueryExpandedFrontierDocument.parse(1).value(),
                TraversalProgressDocument(1, 1, 0, 0),
                TraversalStrategyDocument.BreadthFirst,
                BoundedProtocolList.create(emptyList<TraversalPartialExpansionDocument>()).value(),
                QueryWalkCoverageDocument.Complete,
                io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument.WORKSPACE,
                fixtureWalkDomain(),
                io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint.parse("1".repeat(64)).value(),
            )
        val expected = checkNotNull(javaClass.getResource("/query/walk-empty-domain.json")).readText()
        val encoded =
            wireJson.encodeToJsonElement(QueryWalkObservationWireDocument.serializer(), observation.toWireDocument())
        assertEquals(wireJson.parseToJsonElement(expected), encoded)
        for (field in listOf("requested_domain", "effective_domain", "domain_fingerprint")) {
            val missing = checkNotNull(javaClass.getResource("/query/walk-empty-missing-$field.json")).readText()
            assertThrows(SerializationException::class.java) {
                wireJson.decodeFromString(QueryWalkObservationWireDocument.serializer(), missing)
            }
        }
        assertEquals(WireDocumentConversion.Converted(observation), observation.toWireDocument().toContract())
    }

    private fun partialObservation(remainder: TraversalExpansionRemainderDocument): QueryWalkObservationDocument {
        val partial =
            TraversalPartialExpansionDocument.create(
                    ProtocolText.parse("exact:fixture-node").value(),
                    TraversalDepthDocument.parse(2).value(),
                    listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED),
                    remainder,
                    knownMinimum = QueryKnownMinimum.parse(0).value(),
                    omissions = unmeasuredPageOmissions(),
                )
                .value()
        return QueryWalkObservationDocument(
            QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:fixture-node").value()),
            RelationKindDocument.CALLEES,
            ProtocolCount.parse(3).value(),
            QueryExpandedFrontierDocument.parse(1).value(),
            TraversalProgressDocument(1, 2, 3, 2),
            TraversalStrategyDocument.BreadthFirst,
            BoundedProtocolList.create(listOf(partial)).value(),
            QueryWalkCoverageDocument.resumable(
                    listOf(TraversalLimitationDocument.ONE_HOP_INCOMPLETE),
                    listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED),
                )
                .value(),
            requestedDomain = io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument.WORKSPACE,
            effectiveDomain = fixtureWalkDomain(),
            domainFingerprint =
                io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint.parse("1".repeat(64)).value(),
        )
    }

    private fun unmeasuredPageOmissions(): BoundedProtocolList<RelationOmissionDocument> =
        BoundedProtocolList.create(
                listOf(
                    RelationOmissionDocument.create(
                            RelationProviderDocument.INTELLIJ_CALLEES_V2,
                            RelationLimitationDocument.RESULT_LIMIT_REACHED,
                            RelationOmissionMeasurementDocument.UnmeasuredOnPage,
                            io.github.amichne.kast.protocol.contract.RelationOmissionSamplesDocument.complete(
                                    BoundedProtocolList.create(emptyList<RelationOmissionLocationDocument>()).value()
                                )
                                .value(),
                        )
                        .value()
                )
            )
            .value()

    private fun <T, F> Refinement<T, F>.value(): T = (this as Refinement.Refined).value
}

private fun fixtureWalkDomain(): io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument =
    io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument(
        io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument.Workspace,
        io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST,
        io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument.All,
        null,
        null,
        (io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(
                emptyList<io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument>()
            ) as io.github.amichne.kast.kernel.Refinement.Refined)
            .value,
    )
