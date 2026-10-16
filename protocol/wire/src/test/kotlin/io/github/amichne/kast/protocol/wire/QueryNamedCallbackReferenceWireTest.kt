package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackReferenceReceiverDocument
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceFlowDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExtentDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryNamedCallbackReferenceWireTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `callee walk rejects a valid caller occurrence projection`() {
        rejectCallerProjection(invocationSite = false)
    }

    @Test
    fun `callee walk rejects a valid caller invocation projection`() {
        rejectCallerProjection(invocationSite = true)
    }

    private fun rejectCallerProjection(invocationSite: Boolean) {
        val walk = namedReferenceWalk()
        assertTrue(decodeWalk(walk) is WireDocumentConversion.Converted)
        val entry = walk.callableObservations.single()
        val target = entry.observation.target as QueryCallableTargetWireDocument.NamedReference
        val projection = target.resultProjection as QueryCallbackResultProjectionWireDocument.Admitted
        val flow = target.reference.flow as QueryNamedCallbackReferenceFlowWireDocument.Supplied
        val site =
            if (invocationSite) QueryCallbackResultSiteWireDocument.Invocation(flow.invocations.single())
            else QueryCallbackResultSiteWireDocument.Occurrence(entry.observation.occurrence)
        val caller =
            entry.observation.copy(
                target =
                    target.copy(
                        resultProjection = projection.copy(relation = RelationKindDocument.CALLERS, site = site)
                    )
            )
        val admittedCaller = (caller.toContract() as WireDocumentConversion.Converted).value
        assertTrue(
            admittedCaller.admitsQuestion(
                RelationKindDocument.CALLERS,
                entry.effectiveDomain,
                entry.domainFingerprint,
            )
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            decodeWalk(walk.copy(callableObservations = listOf(entry.copy(observation = caller)))),
        )
    }

    private fun namedReferenceWalk(): QueryWalkObservationWireDocument {
        val callback = fixture.callbackDocument()
        val subject = QueryReferenceDocument.ExactSymbol(fixture.text("exact:seed"))
        val callable =
            QueryWalkCallableObservationDocument(
                subject,
                TraversalDepthDocument.parse(0).value(),
                suppliedReference(),
                callback.requestedDomain,
                callback.effectiveDomain,
                callback.domainFingerprint,
            )
        return QueryWalkObservationDocument(
                subject = subject,
                relation = RelationKindDocument.CALLEES,
                extent = TraversalExtentDocument.ThroughDepth(ProtocolCount.parse(1).value()),
                expandedFrontier = QueryExpandedFrontierDocument.parse(1).value(),
                progress = TraversalProgressDocument(1, 1, 0, 0),
                strategy = TraversalStrategyDocument.BreadthFirst,
                partialExpansions = BoundedProtocolList.create(emptyList<TraversalPartialExpansionDocument>()).value(),
                coverage = QueryWalkCoverageDocument.Complete,
                requestedDomain = callback.requestedDomain,
                effectiveDomain = callback.effectiveDomain,
                domainFingerprint = callback.domainFingerprint,
                callableObservations = BoundedProtocolList.create(listOf(callable)).value(),
            )
            .toWireDocument()
    }

    private fun decodeWalk(
        wire: QueryWalkObservationWireDocument
    ): WireDocumentConversion<QueryWalkObservationDocument> {
        val raw = wireJson.encodeToString(QueryWalkObservationWireDocument.serializer(), wire)
        return wireJson.decodeFromString(QueryWalkObservationWireDocument.serializer(), raw).toContract()
    }

    private fun <V, F> Refinement<V, F>.value(): V = (this as Refinement.Refined).value

    @Test
    fun `named callable supply is distinct from invocation and preserves receivers`() {
        val value = suppliedReference()
        val wire = value.toWireDocument()
        assertEquals(WireDocumentConversion.Converted(value), wire.toContract())
        val raw = wireJson.encodeToJsonElement(QueryCallableObservationWireDocument.serializer(), wire).jsonObject
        val target = raw.getValue("target").jsonObject
        assertEquals(JsonPrimitive("NAMED_REFERENCE"), target.getValue("type"))
        val evidence = target.getValue("reference").jsonObject
        assertEquals(setOf("target", "dispatch_receiver", "extension_receiver", "flow"), evidence.keys)
        assertEquals(JsonPrimitive("UNBOUND"), evidence.getValue("extension_receiver").jsonObject.getValue("type"))
        assertEquals(setOf("type", "binding", "invocations", "forwarding"), evidence.getValue("flow").jsonObject.keys)
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallableObservationWireDocument.serializer(),
                raw.toString().replace("UNBOUND", "UNKNOWN"),
            )
        }
        val named = wire.target as QueryCallableTargetWireDocument.NamedReference
        val outside =
            QueryCallbackReferenceReceiverWireDocument.Bound(
                RelationOccurrenceWireDocument("candidate:elsewhere", "Elsewhere.kt", SourceRangeWireDocument(0, 1))
            )
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(target = named.copy(reference = named.reference.copy(dispatchReceiver = outside))).toContract(),
        )
        val supplied = named.reference.flow as QueryNamedCallbackReferenceFlowWireDocument.Supplied
        val invalidBinding = supplied.binding as QueryCallbackBindingWireDocument.Bound
        val forged =
            wire.copy(
                target =
                    named.copy(
                        reference =
                            named.reference.copy(flow = supplied.copy(binding = invalidBinding.copy(position = 100)))
                    )
            )
        assertEquals(WireDocumentConversion.Rejected, forged.toContract())
    }

    private fun suppliedReference(): QueryCallableObservationDocument {
        val observation = fixture.callbackDocument()
        val flow = observation.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.Bound
        val reference =
            QueryNamedCallbackReferenceDocument(
                observation.target,
                QueryCallbackReferenceReceiverDocument.Absent,
                QueryCallbackReferenceReceiverDocument.Unbound,
                QueryNamedCallbackReferenceFlowDocument.Supplied(binding, flow.invocations, flow.forwarding),
            )
        return (QueryCallableObservationDocument.create(
                observation.callbackBody,
                observation.lexicalOwner,
                QueryCallbackBodyDocument.Named(observation.lexicalOwner),
                QueryCallableTargetDocument.NamedReference(reference, observation.resultProjection),
            ) as Refinement.Refined)
            .value
    }

    @Test
    fun `unused complete formal retains an empty invocation inventory`() {
        val observation = fixture.callbackDocument()
        val flow = observation.flow as QueryCallbackFlowDocument.Observed
        val reference =
            QueryNamedCallbackReferenceDocument(
                observation.target,
                QueryCallbackReferenceReceiverDocument.Absent,
                QueryCallbackReferenceReceiverDocument.Absent,
                QueryNamedCallbackReferenceFlowDocument.Supplied(
                    flow.binding as QueryCallbackBindingDocument.Bound,
                    (BoundedProtocolList.create(
                            emptyList<io.github.amichne.kast.protocol.contract.QueryCallbackInvocationDocument>()
                        ) as Refinement.Refined)
                        .value,
                    flow.forwarding,
                ),
            )
        val value =
            (QueryCallableObservationDocument.create(
                    observation.callbackBody,
                    observation.lexicalOwner,
                    QueryCallbackBodyDocument.Named(observation.lexicalOwner),
                    QueryCallableTargetDocument.NamedReference(reference, observation.resultProjection),
                ) as Refinement.Refined)
                .value
        val raw = value.toWireDocument()
        assertEquals(WireDocumentConversion.Converted(value), raw.toContract())
        val proof =
            (raw.target as QueryCallableTargetWireDocument.NamedReference).reference.flow
                as QueryNamedCallbackReferenceFlowWireDocument.Supplied
        assertEquals(emptyList<QueryCallbackInvocationWireDocument>(), proof.invocations)
    }
}
