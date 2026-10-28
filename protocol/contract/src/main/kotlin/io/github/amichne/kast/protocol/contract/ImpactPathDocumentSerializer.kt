package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The external classification is redundant evidence and must agree with every retained step. */
@Serializable
@SerialName("io.github.amichne.kast.protocol.contract.ImpactPathDocument")
private data class EncodedImpactPath(
    val producer: ImpactValueSiteReferenceDocument,
    val steps: BoundedProtocolList<ImpactPathStepDocument>,
    val representation: ImpactRepresentationEvidenceDocument,
    val terminal: ImpactPathTerminalDocument,
    val classification: ImpactPathClassificationDocument,
)

object ImpactPathDocumentSerializer : KSerializer<ImpactPathDocument> {
    override val descriptor = EncodedImpactPath.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ImpactPathDocument) =
        encoder.encodeSerializableValue(
            EncodedImpactPath.serializer(),
            EncodedImpactPath(
                producer = value.producer,
                steps = value.steps,
                representation = value.representation,
                terminal = value.terminal,
                classification = value.classification,
            ),
        )

    override fun deserialize(decoder: Decoder): ImpactPathDocument {
        val encoded = decoder.decodeSerializableValue(EncodedImpactPath.serializer())
        val path =
            ImpactPathDocument(
                producer = encoded.producer,
                steps = encoded.steps,
                representation = encoded.representation,
                terminal = encoded.terminal,
            )
        if (encoded.classification != path.classification)
            throw SerializationException("Path classification does not match its retained steps")
        return path
    }
}
