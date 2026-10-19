package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackOwnerExecutionDepthWireTest {
    @Test
    fun `wire conversion preserves the nearest admitted owner depth`() {
        assertTrue(observation(63).toContract() is WireDocumentConversion.Converted)
    }

    @Test
    fun `wire conversion rejects the first excessive owner depth`() {
        assertEquals(WireDocumentConversion.Rejected, observation(64).toContract())
    }

    @Test
    fun `wire conversion rejects far excessive owner depth without exhausting the stack`() {
        assertEquals(WireDocumentConversion.Rejected, observation(1000).toContract())
    }

    @Test
    fun `generated codec preserves the nearest admitted owner depth`() {
        assertTrue(decode(63) is WireDecoding.Decoded)
    }

    @Test
    fun `generated codec rejects far excessive owner depth without exhausting the stack`() {
        assertEquals(WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)), decode(1000))
    }

    @Test
    fun `query response rejects far excessive owner depth through its actual decoder`() {
        val leaf = observation(1)
        val envelope =
            WireEnvelopeDocument(
                CanonicalOperationWireBindings.queryRun.schema.value,
                CanonicalOperation.QUERY_RUN.id.value,
                WireBodyDocument.Complete(
                    generation = 1,
                    result = wireJson.encodeToJsonElement(QueryRunResultWireDocument.serializer(), result(leaf)),
                ),
            )
        val leafEncoded = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), leaf)
        val parts = wireJson.encodeToString(WireEnvelopeDocument.serializer(), envelope).split(leafEncoded)
        assertEquals(2, parts.size)
        val encoded = parts.first() + encodedObservationText(1000) + parts.last()
        assertTrue(encoded.toByteArray().size < 4_194_304)
        assertTrue(encoded.toByteArray().size < io.github.amichne.kast.kernel.MAXIMUM_TRANSPORT_BYTES)
        assertEquals(
            WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
            CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded),
        )
    }

    private fun result(leaf: QueryCallbackObservationWireDocument) =
        QueryRunResultWireDocument(
            question =
                QueryQuestionDocument(
                    QueryFromDocument.Location(QueryCallbackWireFixture().text("Seed.kt"), offset()),
                    bounded(emptyList()),
                    QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
            impactAccounting = ImpactAccountingDocument.NotApplicable,
            items = emptyList(),
            failures = emptyList(),
            omissions = emptyList(),
            walkObservations = emptyList(),
            relationObservations =
                listOf(
                    QueryRelationObservationWireDocument(
                        QueryReferenceWireDocument.ExactSymbol("exact:seed"),
                        RelationKindDocument.CALLEES,
                        RelationProviderDocument.INTELLIJ_CALLEES_V2,
                        leaf.requestedDomain,
                        leaf.effectiveDomain,
                        leaf.domainFingerprint,
                        QueryRelationCoverageDocument.Exhausted,
                        emptyList(),
                        listOf(leaf),
                        emptyList(),
                    )
                ),
            retention = QueryResultRetention.NotRequested,
        )

    private fun decode(depth: Int) =
        GeneratedWireCodecFactory(wireJson)
            .create(
                QueryCallbackObservationWireDocument.serializer(),
                QueryCallbackObservationDocument::toWireDocument,
                QueryCallbackObservationWireDocument::toContract,
            )
            .decode(wireJson.parseToJsonElement(encodedObservationText(depth)), WireValueRole.RESULT)

    private fun encodedObservationText(depth: Int): String {
        val leafObservation = observation(1)
        val leaf = (leafObservation.flow as QueryCallbackFlowWireDocument.Observed).ownerBindings.single()
        val frame = (observation(2).flow as QueryCallbackFlowWireDocument.Observed).ownerBindings.single()
        val encodedLeaf = wireJson.encodeToString(QueryCallbackBodyBindingWireDocument.serializer(), leaf)
        val frameParts =
            wireJson.encodeToString(QueryCallbackBodyBindingWireDocument.serializer(), frame).split(encodedLeaf)
        val observationParts =
            wireJson
                .encodeToString(QueryCallbackObservationWireDocument.serializer(), leafObservation)
                .split(encodedLeaf)
        assertEquals(2, frameParts.size)
        assertEquals(2, observationParts.size)
        return buildString {
            append(observationParts.first())
            repeat(depth - 1) { append(frameParts.first()) }
            append(encodedLeaf)
            repeat(depth - 1) { append(frameParts.last()) }
            append(observationParts.last())
        }
    }

    private fun offset() = (ProtocolOffset.parse(0) as Refinement.Refined).value

    private fun <T> bounded(values: List<T>) = (BoundedProtocolList.create(values) as Refinement.Refined).value

    private fun observation(depth: Int): QueryCallbackObservationWireDocument {
        val wire = QueryCallbackOwnerExecutionWireFixture().composed()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val template = flow.ownerBindings.single()
        val execution = (template.execution as QueryCallbackOwnerExecutionWireDocument.Observed).flow
        val nested = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION)
        var owner = template.copy(obligations = nested, execution = QueryCallbackOwnerExecutionWireDocument.Unproven)
        repeat(depth - 1) {
            owner =
                template.copy(
                    obligations = nested,
                    execution =
                        QueryCallbackOwnerExecutionWireDocument.Observed(
                            execution.copy(ownerBindings = listOf(owner), obligations = nested)
                        ),
                )
        }
        return wire.copy(flow = flow.copy(ownerBindings = listOf(owner), obligations = nested))
    }
}
