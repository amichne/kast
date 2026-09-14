package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedEndpointEncodingFixtureTest {
    @Test
    fun `every endpoint failure encoding matches the independently schema checked fixture`() {
        val report = report()
        val documents =
            HostedEndpointFailure.entries.flatMap { failure ->
                val bare = HostedRequests.rejected(failure)
                if (failure in listOf(HostedEndpointFailure.RESULT_TOO_LARGE, HostedEndpointFailure.RESPONSE_REJECTED))
                    listOf(bare, HostedRequests.rejected(failure, ExecutionBudgetPresence.Present(report)))
                else listOf(bare)
            }
        val actual = Json.encodeToString(FailureDocuments(documents))
        val reportPath = Path.of("build/reports/hosted-endpoint-failure-encodings.json")
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, actual)
        assertEquals(
            Files.readString(Path.of("../../cli/src/test/resources/hosted-endpoint-failure-encodings.json")).trim(),
            actual,
        )
    }

    @Serializable private data class FailureDocuments(val documents: List<String>)

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
