@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RunnerControlledBudgetReportTest {
    @Test
    fun `runner report emits only finite output grants with explicit mode`() {
        for (timeout in listOf(null, 60001L)) {
            val elapsed = ElapsedTimeLimitMillis.parse(1).proven()
            val results = ResultLimit.parse(2).proven()
            val bytes = ReturnedByteLimit.parse(2000).proven()
            val resources = ResourceBudget(results, WorkUnitLimit.parse(1).proven(), elapsed)
            val grant =
                AdmittedExecutionBudget.admit(
                    RequestedExecutionBudget.RunnerControlled(
                        timeout = timeout?.let { ElapsedTimeLimitMillis.parse(it).proven() }
                    ),
                    resources,
                    bytes,
                    resources,
                    bytes,
                    ExecutionBudgetCapacity(elapsed, results, bytes),
                )
            val report = ExecutionBudgetReport.from(grant)
            val json = Json { encodeDefaults = true }
            val expected =
                json.encodeToString(ExpectedRunnerReport.serializer(), ExpectedRunnerReport(timeout = timeout))
            val actual = json.encodeToString(ExecutionBudgetReport.serializer(), report)
            assertEquals(json.parseToJsonElement(expected), json.parseToJsonElement(actual))
            assertEquals(report, json.decodeFromString(ExecutionBudgetReport.serializer(), expected))
        }
    }

    @Test
    fun `runner report rejects a finite semantic grant and unknown mode`() {
        val json = Json { encodeDefaults = true }
        for (input in
            listOf(
                json.encodeToString(IncompatibleRunnerReport.serializer(), IncompatibleRunnerReport()),
                json.encodeToString(ExpectedRunnerReport.serializer(), ExpectedRunnerReport(mode = "UNLIMITED")),
            )) assertThrows(SerializationException::class.java) {
            json.decodeFromString(ExecutionBudgetReport.serializer(), input)
        }
    }
}

@Serializable
private data class ExpectedRunnerReport(
    val mode: String = "RUNNER_CONTROLLED",
    @SerialName("max_results") val results: ExpectedOutputGrant = ExpectedOutputGrant(2),
    @SerialName("max_returned_bytes") val bytes: ExpectedOutputGrant = ExpectedOutputGrant(2000),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("max_elapsed_ms")
    val timeout: Long? = null,
)

@Serializable
private data class ExpectedOutputGrant(
    val configuredDefault: Long,
    val selection: ExecutionBudgetSelectionDocument = ExecutionBudgetSelectionDocument.CONFIGURED_DEFAULT,
    val requested: Long? = null,
    val operatorCeiling: Long = configuredDefault,
    val effective: Long = configuredDefault,
    val clamping: List<ExecutionBudgetClampDocument> = emptyList(),
)

/** Deliberately incompatible wire input exercises rejection of semantic grants in runner mode. */
@Serializable
private data class IncompatibleRunnerReport(
    val mode: String = "RUNNER_CONTROLLED",
    @SerialName("max_elapsed_ms") val elapsed: ExpectedOutputGrant = ExpectedOutputGrant(1),
    @SerialName("max_results") val results: ExpectedOutputGrant = ExpectedOutputGrant(2),
    @SerialName("max_returned_bytes") val bytes: ExpectedOutputGrant = ExpectedOutputGrant(2000),
)

private fun <Value> Refinement<Value, *>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid report fixture")
    }
