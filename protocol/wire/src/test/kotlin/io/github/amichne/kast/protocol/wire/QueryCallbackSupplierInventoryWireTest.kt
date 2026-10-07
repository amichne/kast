package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterIdentityDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierInventoryDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierPartitionDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCallbackSupplierInventoryWireTest {
    @Test
    fun `exhausted empty root is explicit and omitting or duplicating its partition rejects`() {
        val callback = QueryCallbackWireFixture().callbackDocument()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.Bound
        val formal = QueryCallbackParameterIdentityDocument(binding.callable, binding.position, binding.parameter)
        val partition = QueryCallbackSupplierPartitionDocument(formal, bounded(emptyList()), bounded(emptyList()))
        val proof =
            (QueryCallbackSupplierInventoryDocument.Exhaustive.create(
                    formal,
                    flow.basis,
                    callback.domainFingerprint,
                    bounded(listOf(partition)),
                ) as Refinement.Refined)
                .value
        val wire = proof.supplierInventoryWire() as QueryCallbackSupplierInventoryWireDocument.Exhaustive
        val encoded =
            wireJson.encodeToJsonElement(QueryCallbackSupplierInventoryWireDocument.serializer(), wire).jsonObject
        assertEquals(setOf("type", "root", "basis", "domain", "partitions"), encoded.keys)
        assertEquals(JsonPrimitive("EXHAUSTIVE"), encoded.getValue("type"))
        val serializedPartition = encoded.getValue("partitions").jsonArray.single().jsonObject
        assertEquals(setOf("formal", "suppliers", "incoming"), serializedPartition.keys)
        assertEquals(0, serializedPartition.getValue("suppliers").jsonArray.size)
        assertEquals(0, serializedPartition.getValue("incoming").jsonArray.size)
        val admitted = wire.toContract() as WireDocumentConversion.Converted
        val restored = admitted.value as QueryCallbackSupplierInventoryDocument.Exhaustive
        assertEquals(formal, restored.root)
        assertEquals(flow.basis, restored.basis)
        assertEquals(callback.domainFingerprint, restored.domain)
        assertEquals(listOf(partition), restored.partitions.values)
        assertEquals(WireDocumentConversion.Rejected, wire.copy(partitions = emptyList()).toContract())
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(partitions = wire.partitions + wire.partitions).toContract(),
        )
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallbackSupplierInventoryWireDocument.serializer(),
                encoded.toString().replace("EXHAUSTIVE", "UNKNOWN"),
            )
        }
    }

    private fun <T> bounded(values: List<T>) = (BoundedProtocolList.create(values) as Refinement.Refined).value
}
