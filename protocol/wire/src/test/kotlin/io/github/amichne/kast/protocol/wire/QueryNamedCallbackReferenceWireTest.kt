package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackReferenceReceiverDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceFlowDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryNamedCallbackReferenceWireTest {
    private val fixture = QueryCallbackWireFixture()

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
                QueryCallableTargetDocument.NamedReference(reference),
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
                    QueryCallableTargetDocument.NamedReference(reference),
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
