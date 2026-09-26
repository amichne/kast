package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedContainmentBudgetTest {
    @Test
    fun `hard frame admission preserves candidate and checks every endpoint failure encoding`() {
        val report = report()
        val small =
            ReadLimits.resolve(
                    environment =
                        mapOf(
                            "KAST_READ_HOST_RESPONSE_BYTES" to "256",
                            "KAST_READ_SEMANTIC_RETURNED_BYTES" to "256",
                            "KAST_READ_SOURCE_RETURNED_BYTES" to "256",
                        )
                )
                .proven()
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.RESULT_LIMIT_EXCEEDED),
            hostedReadPublicationAdmission.admit(report, small),
        )
        assertEquals(Refinement.Refined(Unit), hostedReadPublicationAdmission.admit(report, ReadLimits.Default))
        for (failure in HostedEndpointFailure.entries) {
            val encoded =
                HostedRequests.rejected(
                    failure,
                    ExecutionBudgetPresence.Present(report),
                )
            assertTrue(
                encoded.toByteArray(Charsets.UTF_8).size <=
                    ReadLimits.Default[ReadLimitParameter.HOST_RESPONSE_BYTES].value
            )
            assertEquals(
                Json.encodeToJsonElement(ExecutionBudgetReport.serializer(), report),
                Json.parseToJsonElement(encoded).jsonObject["execution_budget"],
            )
        }
    }

    @Test
    fun `every read preserves the admitted rejection when its complete envelope cannot fit`() {
        val report = report()
        val bytes = ReturnedByteLimit.parse(1).proven()
        val resultLimit = ResultLimit.parse(1).proven()
        val responses =
            listOf(
                encodeHostedQueryResponse(
                    OperationOutcome.Rejected(QueryRunRejection.WorkspaceNotReady).withQueryBudget(report),
                    maximumBytes = bytes,
                ),
                encodeHostedSourceResponse(
                    OperationOutcome.Rejected(SourceReadRejection.CONTRACT_VIOLATION).withSourceBudget(report),
                    ReadLimits.Default,
                    resultLimit,
                    bytes,
                ) {
                    error("No suffix")
                },
                encodeHostedTraversalResponse(
                    OperationOutcome.Rejected(TraversalRunRejection.SELECTOR_STALE).withTraversalBudget(report),
                    ReadLimits.Default,
                    resultLimit,
                    bytes,
                ) {
                    error("No suffix")
                },
            )
        for (response in responses) {
            assertEquals(true, (response as HostedResponse.Oversized).semantic is OperationOutcome.Rejected)
            assertReport(response, report, HostedEndpointFailure.RESULT_TOO_LARGE)
        }
    }

    @Test
    fun `publication failures retain the admitted report in the actual hosted response`() = runTest {
        val report = report()
        val original = HostedSourcePagingFixture.create().outcome.withSourceBudget(report)
        for ((bytes, retention, failure) in
            listOf(
                Triple(1L, HostedOutputRetention.CapacityExceeded, HostedEndpointFailure.RESULT_TOO_LARGE),
                Triple(100_000L, HostedOutputRetention.CapacityExceeded, HostedEndpointFailure.RESULT_TOO_LARGE),
                Triple(100_000L, HostedOutputRetention.EncodingRejected, HostedEndpointFailure.RESPONSE_REJECTED),
            )) {
            val response =
                encodeHostedSourceResponse(
                    original,
                    ReadLimits.Default,
                    ResultLimit.parse(1).proven(),
                    ReturnedByteLimit.parse(bytes).proven(),
                ) {
                    retention
                }
            assertReport(response, report, failure)
        }
    }

    private fun assertReport(response: HostedResponse, report: ExecutionBudgetReport, failure: HostedEndpointFailure) {
        val encoded = Json.parseToJsonElement(response.document).jsonObject
        assertEquals("HOST_REJECTED", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(failure.name, encoded.getValue("failure").jsonPrimitive.content)
        assertEquals(Json.encodeToJsonElement(ExecutionBudgetReport.serializer(), report), encoded["execution_budget"])
    }

    private fun report(): ExecutionBudgetReport {
        val resources =
            ResourceBudget(
                ResultLimit.parse(8).proven(),
                WorkUnitLimit.parse(7).proven(),
                ElapsedTimeLimitMillis.parse(100).proven(),
            )
        val bytes = ReturnedByteLimit.parse(4096).proven()
        return ExecutionBudgetReport.from(
            AdmittedExecutionBudget.admit(
                RequestedExecutionBudget(),
                resources,
                bytes,
                resources,
                bytes,
                ExecutionBudgetCapacity(resources.elapsedTimeLimit, resources.resultLimit, bytes),
            )
        )
    }

    private fun <T> Refinement<T, *>.proven(): T = (this as Refinement.Refined).value
}
