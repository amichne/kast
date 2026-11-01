package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.requestedExecutionBudget
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PublicCliBudgetTest {
    @Test
    fun `omitted flag budgets use varied configured defaults without a semantic timeout`() {
        val command =
            (parsePublicCliArguments(listOf("query", "--name", "OrderService")) as Refinement.Refined).value
                as PublicCliCommand.Query
        val input = (command.input as PublicCliInput.Lowered).arguments
        val admitted =
            (PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input, command.catalog) as Refinement.Refined)
                .value
        val run = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val requested = run.requestedExecutionBudget() as RequestedExecutionBudget.RunnerControlled
        assertEquals(ExecutionAllowance.Default, requested.results)
        assertEquals(ExecutionAllowance.Default, requested.returnedBytes)
        val resultCeiling = ResultLimit.parse(100).refined()
        val byteCeiling = ReturnedByteLimit.parse(32768).refined()
        for ((results, bytes) in listOf(7 to 8192L, 13 to 16384L)) {
            val grant =
                AdmittedExecutionBudget.admit(
                    request = requested,
                    defaultResults = ResultLimit.parse(results).refined(),
                    defaultBytes = ReturnedByteLimit.parse(bytes).refined(),
                    ceilingResults = resultCeiling,
                    ceilingBytes = byteCeiling,
                    capacityResults = resultCeiling,
                    capacityBytes = byteCeiling,
                )
            assertEquals(results, grant.results.effective.value)
            assertEquals(bytes, grant.returnedBytes.effective.value)
            assertNull(grant.timeout)
        }
    }
}

private fun <Value> Refinement<Value, *>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid independent budget fixture")
    }
