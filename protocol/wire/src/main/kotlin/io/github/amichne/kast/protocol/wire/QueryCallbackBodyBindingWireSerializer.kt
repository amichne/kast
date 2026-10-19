@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackOwnerExecutionLimits
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object QueryCallbackBodyBindingWireSerializer : KSerializer<QueryCallbackBodyBindingWireDocument> {
    private val delegate = QueryCallbackBodyBindingWireDocument.generatedSerializer()

    override val descriptor: SerialDescriptor
        get() = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryCallbackBodyBindingWireDocument) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): QueryCallbackBodyBindingWireDocument {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Callback owner requires JSON")
        val element = json.decodeJsonElement()
        element.admitOwnerExecutionSize()
        return json.json.decodeFromJsonElement(delegate, element)
    }
}

private fun JsonElement.admitOwnerExecutionSize() {
    val pending = ArrayDeque<OwnerExecutionFrame>()
    pending.addLast(OwnerExecutionFrame(this, 1))
    var count = 0
    while (pending.isNotEmpty()) {
        val frame = pending.removeLast()
        if (
            ++count > QueryCallbackOwnerExecutionLimits.MAXIMUM_COUNT ||
                frame.depth >= QueryCallbackOwnerExecutionLimits.EXCLUSIVE_DEPTH
        )
            throw SerializationException("Callback owner execution exceeds document limits")
        frame.enqueueChildren(pending, count)
    }
}

private fun OwnerExecutionFrame.enqueueChildren(pending: ArrayDeque<OwnerExecutionFrame>, count: Int) {
    val execution = (owner as? JsonObject)?.get("execution") as? JsonObject ?: return
    val flow = execution["flow"] as? JsonObject ?: return
    when ((execution["type"] as? JsonPrimitive)?.content) {
        "OBSERVED" -> pending.addOwners(flow["owner_bindings"], depth + 1, count)
        "IMMUTABLE" -> {
            pending.addOwners(flow["owner_bindings"], depth + 1, count)
            pending.addSummaryOwners(flow, depth + 1, count)
        }
        else -> Unit
    }
}

private fun ArrayDeque<OwnerExecutionFrame>.addSummaryOwners(flow: JsonObject, depth: Int, count: Int) {
    for (use in (flow["uses"] as? JsonArray).orEmpty()) {
        val supplied = use as? JsonObject ?: continue
        if ((supplied["type"] as? JsonPrimitive)?.content == "SUPPLIED")
            addOwners(supplied["summary_owner_bindings"], depth, count)
    }
}

private fun ArrayDeque<OwnerExecutionFrame>.addOwners(element: JsonElement?, depth: Int, count: Int) {
    for (owner in (element as? JsonArray).orEmpty()) {
        if (size + count >= QueryCallbackOwnerExecutionLimits.MAXIMUM_COUNT)
            throw SerializationException("Callback owner execution exceeds document limits")
        addLast(OwnerExecutionFrame(owner, depth))
    }
}

private data class OwnerExecutionFrame(val owner: JsonElement, val depth: Int)
