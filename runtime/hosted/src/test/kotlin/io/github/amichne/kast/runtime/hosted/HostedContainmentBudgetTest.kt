package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedContainmentBudgetTest {
    @Test
    fun `publication failures retain the admitted report in the actual hosted response`() = runTest {
        val report = report()
        val original = HostedSourcePagingFixture.create().outcome.withSourceBudget(report)
        for ((bytes, retention, failure) in listOf(
            Triple(1L, HostedOutputRetention.CapacityExceeded, HostedEndpointFailure.RESULT_TOO_LARGE),
            Triple(100_000L, HostedOutputRetention.CapacityExceeded, HostedEndpointFailure.RESULT_TOO_LARGE),
            Triple(100_000L, HostedOutputRetention.EncodingRejected, HostedEndpointFailure.RESPONSE_REJECTED),
        )) {
            val response = encodeHostedSourceResponse(
                original, ReadLimits.Default, ResultLimit.parse(1).proven(), ReturnedByteLimit.parse(bytes).proven(),
            ) { retention }
            val encoded = Json.parseToJsonElement(response.document).jsonObject
            assertEquals("HOST_REJECTED", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(failure.name, encoded.getValue("failure").jsonPrimitive.content)
            assertEquals(Json.encodeToJsonElement(ExecutionBudgetReport.serializer(), report), encoded["execution_budget"])
        }
    }

    private fun report(): ExecutionBudgetReport {
        val resources = ResourceBudget(ResultLimit.parse(8).proven(), WorkUnitLimit.parse(7).proven(),
            ElapsedTimeLimitMillis.parse(100).proven())
        val bytes = ReturnedByteLimit.parse(4096).proven()
        return ExecutionBudgetReport.from(AdmittedExecutionBudget.admit(RequestedExecutionBudget(), resources, bytes,
            resources, bytes, ExecutionBudgetCapacity(resources.elapsedTimeLimit, resources.resultLimit, bytes)))
    }

    private fun <T> Refinement<T, *>.proven(): T = (this as Refinement.Refined).value
}
