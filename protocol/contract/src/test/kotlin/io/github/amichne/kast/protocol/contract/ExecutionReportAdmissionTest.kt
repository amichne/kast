package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExecutionReportAdmissionTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `report amounts reject nonpositive quoted fractional and overflowing values`() {
        for (raw in listOf("0", "-1", "\"10\"", "1.5", "9223372036854775808")) {
            val invalid = json.parseToJsonElement(raw)
            for (document in listOf(
                MalformedExecutionLimit(requested = invalid),
                MalformedExecutionLimit(configuredDefault = invalid),
                MalformedExecutionLimit(operatorCeiling = invalid),
                MalformedExecutionLimit(effective = invalid),
            )) reject(document)
        }
    }

    @Test
    fun `selection and clamping must describe one possible admitted grant`() {
        for (document in listOf(
            MalformedExecutionLimit(requested = null),
            MalformedExecutionLimit(selection = ExecutionBudgetSelectionDocument.CONFIGURED_DEFAULT),
            MalformedExecutionLimit(effective = JsonPrimitive(11)),
            MalformedExecutionLimit(operatorCeiling = JsonPrimitive(9)),
            MalformedExecutionLimit(effective = JsonPrimitive(9)),
            MalformedExecutionLimit(clamping = listOf(ExecutionBudgetClampDocument.TRANSPORT_CAPACITY)),
            MalformedExecutionLimit(effective = JsonPrimitive(5), clamping = listOf(ExecutionBudgetClampDocument.OPERATOR_CEILING)),
            MalformedExecutionLimit(operatorCeiling = JsonPrimitive(5), effective = JsonPrimitive(5), clamping = listOf(ExecutionBudgetClampDocument.OPERATOR_CEILING, ExecutionBudgetClampDocument.OPERATOR_CEILING)),
            MalformedExecutionLimit(operatorCeiling = JsonPrimitive(5), effective = JsonPrimitive(1), clamping = listOf(ExecutionBudgetClampDocument.TRANSPORT_CAPACITY, ExecutionBudgetClampDocument.OPERATOR_CEILING)),
        )) reject(document)
    }

    private fun reject(document: MalformedExecutionLimit) {
        assertThrows(SerializationException::class.java, {
            json.decodeFromString(ExecutionLimitDocument.serializer(), json.encodeToString(MalformedExecutionLimit.serializer(), document))
        }, document.toString())
    }
}

/** Opaque numeric values deliberately prove rejection of malformed output evidence. */
@Serializable
private data class MalformedExecutionLimit(
    val selection: ExecutionBudgetSelectionDocument = ExecutionBudgetSelectionDocument.CALLER,
    val requested: JsonElement? = JsonPrimitive(10),
    val configuredDefault: JsonElement = JsonPrimitive(100),
    val operatorCeiling: JsonElement = JsonPrimitive(100),
    val effective: JsonElement = JsonPrimitive(10),
    val clamping: List<ExecutionBudgetClampDocument> = emptyList(),
)
