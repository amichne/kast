package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExecutionReportDimensionTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `report dimensions reject clamping causes the admitted grant cannot produce`() {
        val transport =
            DimensionLimit(effective = 5, clamping = listOf(ExecutionBudgetClampDocument.TRANSPORT_CAPACITY))
        val deadline = DimensionLimit(effective = 5, clamping = listOf(ExecutionBudgetClampDocument.DEADLINE_REMAINING))
        for (report in
            listOf(
                DimensionReport(elapsed = transport),
                DimensionReport(work = transport),
                DimensionReport(work = deadline),
                DimensionReport(results = deadline),
                DimensionReport(bytes = deadline),
            )) reject(report)
    }

    @Test
    fun `every result amount retains the finite integer range of its domain`() {
        val large = Int.MAX_VALUE.toLong() + 1
        for (results in
            listOf(
                DimensionLimit(configuredDefault = large),
                DimensionLimit(operatorCeiling = large),
                DimensionLimit(
                    requested = large,
                    operatorCeiling = Int.MAX_VALUE.toLong(),
                    effective = Int.MAX_VALUE.toLong(),
                    clamping = listOf(ExecutionBudgetClampDocument.OPERATOR_CEILING),
                ),
                DimensionLimit(requested = large, operatorCeiling = large, effective = large),
            )) reject(DimensionReport(results = results))
    }

    @Test
    fun `valid dimension evidence retains its independently specified wire shape`() {
        val report =
            DimensionReport(
                elapsed =
                    DimensionLimit(effective = 5, clamping = listOf(ExecutionBudgetClampDocument.DEADLINE_REMAINING)),
                results =
                    DimensionLimit(
                        requested = Int.MAX_VALUE.toLong(),
                        operatorCeiling = Int.MAX_VALUE.toLong(),
                        effective = Int.MAX_VALUE.toLong(),
                    ),
                bytes =
                    DimensionLimit(effective = 5, clamping = listOf(ExecutionBudgetClampDocument.TRANSPORT_CAPACITY)),
            )
        val input = json.encodeToString(DimensionReport.serializer(), report)
        val decoded = json.decodeFromString(ExecutionBudgetReport.serializer(), input)
        val encoded = json.encodeToString(ExecutionBudgetReport.serializer(), decoded)
        assertEquals(json.parseToJsonElement(input), json.parseToJsonElement(encoded))
    }

    private fun reject(report: DimensionReport) {
        val input = json.encodeToString(DimensionReport.serializer(), report)
        assertThrows(
            SerializationException::class.java,
            {
                json.decodeFromString(ExecutionBudgetReport.serializer(), input)
            },
            report.toString(),
        )
    }
}

/** Fixed deliberately incompatible report fixture; each individual limit is arithmetically consistent. */
@Serializable
private data class DimensionReport(
    @SerialName("max_elapsed_ms") val elapsed: DimensionLimit = DimensionLimit(),
    @SerialName("max_work_units") val work: DimensionLimit = DimensionLimit(),
    @SerialName("max_results") val results: DimensionLimit = DimensionLimit(),
    @SerialName("max_returned_bytes") val bytes: DimensionLimit = DimensionLimit(),
)

@Serializable
private data class DimensionLimit(
    val selection: ExecutionBudgetSelectionDocument = ExecutionBudgetSelectionDocument.CALLER,
    val requested: Long? = 10,
    val configuredDefault: Long = 100,
    val operatorCeiling: Long = 100,
    val effective: Long = 10,
    val clamping: List<ExecutionBudgetClampDocument> = emptyList(),
)
