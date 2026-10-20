package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object QueryCallbackBodyBindingWireSerializer :
    KSerializer<QueryCallbackBodyBindingWireDocument> by BoundedCallbackProofSerializer(
        QueryCallbackBodyBindingWireDocument.generatedSerializer()
    )

object ImpactCallFrameWireSerializer :
    KSerializer<ImpactCallFrameDocument> by BoundedCallbackProofSerializer(
        ImpactCallFrameDocument.generatedSerializer()
    )

object QueryObservedCallbackFlowWireSerializer :
    KSerializer<QueryCallbackFlowWireDocument.Observed> by BoundedCallbackProofSerializer(
        QueryCallbackFlowWireDocument.Observed.serializer()
    )

object QueryCallbackFlowWireSerializer :
    KSerializer<QueryCallbackFlowWireDocument> by BoundedCallbackProofSerializer(
        QueryCallbackFlowWireDocument.serializer()
    )

object QueryImmutableCallbackFlowWireSerializer :
    KSerializer<QueryImmutableCallbackFlowWireDocument> by BoundedCallbackProofSerializer(
        QueryImmutableCallbackFlowWireDocument.generatedSerializer()
    )

object QueryImmutableCallbackValueWireSerializer :
    KSerializer<QueryImmutableCallbackValueWireDocument> by BoundedCallbackProofSerializer(
        QueryImmutableCallbackValueWireDocument.generatedSerializer()
    )

object QueryImmutableCallbackValueNodeWireSerializer :
    KSerializer<QueryImmutableCallbackValueNodeWireDocument> by BoundedCallbackProofSerializer(
        QueryImmutableCallbackValueNodeWireDocument.generatedSerializer()
    )

private class BoundedCallbackProofSerializer<T>(private val delegate: KSerializer<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor
        get() = delegate.descriptor

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeSerializableValue(delegate, value)

    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Callback proof requires JSON")
        val element = json.decodeJsonElement()
        element.admitCallbackProofSize()
        return json.json.decodeFromJsonElement(delegate, element)
    }
}

/** Count expanded proof edges before generated recursive decoding, without shared mutable state. */
private fun JsonElement.admitCallbackProofSize() {
    val pending = ArrayDeque<CallbackProofDecodeNode>()
    pending.addLast(CallbackProofDecodeNode(this, 0))
    var count = 0
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        val element = current.element
        val proofNode = element is JsonObject && (element.isOwnerBinding() || element.isCallFrame())
        val depth = current.depth + if (proofNode) 1 else 0
        if (
            proofNode &&
                (++count > QueryCallbackOwnerExecutionLimits.MAXIMUM_COUNT ||
                    depth >= QueryCallbackOwnerExecutionLimits.EXCLUSIVE_DEPTH)
        ) {
            throw SerializationException("Callback proof exceeds document limits")
        }
        for (child in element.proofChildren()) pending.addLast(CallbackProofDecodeNode(child, depth))
    }
}

private fun JsonElement.proofChildren(): Collection<JsonElement> =
    when (this) {
        is JsonObject -> values
        is JsonArray -> this
        else -> emptyList()
    }

private fun JsonObject.isOwnerBinding(): Boolean =
    "body" in this && "supply" in this && "binding" in this && "obligations" in this && "execution" in this

private fun JsonObject.isCallFrame(): Boolean = "enclosing" in this && "invocation" in this && "callee" in this

private data class CallbackProofDecodeNode(val element: JsonElement, val depth: Int)
