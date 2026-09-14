package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Absent is an omitted boundary field; an explicit null is never an admitted report. */
@Serializable(with = ExecutionBudgetPresenceSerializer::class)
sealed interface ExecutionBudgetPresence {
    data object Absent : ExecutionBudgetPresence

    data class Present(val report: ExecutionBudgetReport) : ExecutionBudgetPresence
}

object ExecutionBudgetPresenceSerializer : KSerializer<ExecutionBudgetPresence> {
    override val descriptor = ExecutionBudgetReport.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ExecutionBudgetPresence) =
        when (value) {
            ExecutionBudgetPresence.Absent -> throw SerializationException("Absent execution budget must be omitted")
            is ExecutionBudgetPresence.Present ->
                encoder.encodeSerializableValue(ExecutionBudgetReport.serializer(), value.report)
        }

    override fun deserialize(decoder: Decoder): ExecutionBudgetPresence =
        ExecutionBudgetPresence.Present(decoder.decodeSerializableValue(ExecutionBudgetReport.serializer()))
}
