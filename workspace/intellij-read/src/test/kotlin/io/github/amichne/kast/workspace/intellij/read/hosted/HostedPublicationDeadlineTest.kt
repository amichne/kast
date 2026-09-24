package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedPublicationDeadlineTest {
    @Test fun `operator-clamped one-millisecond report fits after deadline clamp appears`() = assertCeilingPlateau(1L)

    @Test fun `operator-clamped four-digit report fits after deadline clamp appears`() = assertCeilingPlateau(1000L)

    private fun assertCeilingPlateau(ceiling: Long) {
        val limits =
            ReadLimits.resolve(
                    environment =
                        mapOf(
                            "KAST_READ_EXECUTION_MAX_MILLIS" to ceiling.toString(),
                            "KAST_READ_HOST_QUERY_MILLIS" to "4000",
                            "KAST_READ_HOST_CONNECTION_MILLIS" to "5000",
                            "KAST_READ_CLIENT_EXCHANGE_MILLIS" to "6000",
                        )
                )
                .proven()
        var now = 0L
        val checkedSizes = mutableListOf<Int>()
        fun bytes(report: ExecutionBudgetReport) =
            Json.encodeToString(ExecutionBudgetReport.serializer(), report).toByteArray(Charsets.UTF_8).size
        val progress =
            HostedQueryProgress(
                limits,
                { now },
                publication =
                    HostedReadPublicationAdmission { report, _ ->
                        checkedSizes += bytes(report)
                        now = 2_250_000_000L
                        Refinement.Refined(Unit)
                    },
            )
        val allowance = progress.admitSemanticTime().proven()
        val report = ExecutionBudgetReport.from(allowance.executionBudget)
        assertEquals(ceiling, allowance.semantic.value)
        assertEquals(1500L, allowance.diagnosticScope.value)
        assertTrue(
            bytes(report) <= checkedSizes.max(),
            "ceiling=$ceiling, final=${bytes(report)}, checked=$checkedSizes",
        )
        assertEquals(ExecutionBudgetPresence.Present(report), progress.executionBudget)
    }

    @Test
    fun `publication witnesses bound all elapsed digit and clamp transitions including maximum inputs`() {
        val policies =
            listOf(ReadLimits.Default) +
                listOf(1L, 10L, 100L, 800L, 1000L).map { ceiling ->
                    ReadLimits.resolve(environment = mapOf("KAST_READ_EXECUTION_MAX_MILLIS" to ceiling.toString()))
                        .proven()
                }
        val requests =
            listOf(HostedExecutionBudgetRequest()) +
                listOf(1L, 100L, 2000L, Long.MAX_VALUE).map { amount ->
                    HostedExecutionBudgetRequest(
                        RequestedExecutionBudget(
                            elapsed = ExecutionAllowance.Requested(ElapsedTimeLimitMillis.parse(amount).proven())
                        )
                    )
                }
        for (limits in policies) for (request in requests) for (initial in
            listOf(1L, 10L, 1000L, 3750L, Long.MAX_VALUE)) {
            assertPublicationBound(limits, request, initial)
        }
    }

    private fun assertPublicationBound(limits: ReadLimits, request: HostedExecutionBudgetRequest, initial: Long) {
        fun allowance(available: Long) = HostedSemanticTimeAllowance.admit(limits, available, request).proven()
        fun report(value: HostedSemanticTimeAllowance) = ExecutionBudgetReport.from(value.executionBudget)
        fun bytes(value: ExecutionBudgetReport) =
            Json.encodeToString(ExecutionBudgetReport.serializer(), value).toByteArray(Charsets.UTF_8).size
        val candidate = allowance(initial)
        val original = report(candidate)
        val selected =
            when (val supplied = request.requested.elapsed) {
                ExecutionAllowance.Default -> limits[ReadLimitParameter.SEMANTIC_MILLIS].value.toLong()
                is ExecutionAllowance.Requested -> supplied.value.value
            }
        val witness = report(allowance(minOf(candidate.semantic.value, (selected - 1L).coerceAtLeast(1L))))
        val bound = maxOf(bytes(original), bytes(witness))
        val decimalBoundaries =
            generateSequence(10L) { if (it < 1_000_000_000L) it * 10L else null }
                .flatMap { sequenceOf(it - 1L, it) }
                .toList()
        val availableValues =
            (listOf(
                    1L,
                    initial,
                    selected,
                    (selected - 1L).coerceAtLeast(1L),
                    candidate.semantic.value,
                    (candidate.semantic.value - 1L).coerceAtLeast(1L),
                ) + decimalBoundaries)
                .filter { it <= initial }
        for (available in availableValues) {
            val refined = allowance(available)
            val observed = report(refined)
            assertTrue(bytes(observed) <= bound)
            assertEquals(original.work, observed.work)
            assertEquals(original.results, observed.results)
            assertEquals(original.returnedBytes, observed.returnedBytes)
            assertEquals(
                minOf(limits[ReadLimitParameter.DIAGNOSTIC_SCOPE_MILLIS].value.toLong(), available),
                refined.diagnosticScope.value,
            )
        }
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value

    @Test
    fun `publication checks cannot admit provider work after exhausting host time`() = runTest {
        var now = 0L
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { now }) { limits ->
                HostedReadDiagnostics({ now }, limits, receipts::add)
            }
        var providerCalls = 0
        val result =
            executor.execute(
                executor.endpoint,
                shortHostLimits(),
                publication =
                    HostedReadPublicationAdmission { report, limits ->
                        now += 4_000_000_000L
                        HostedReadPublicationAdmission.Containment.admit(report, limits)
                    },
            ) { progress ->
                runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { providerCalls++ }
            } as HostedExecution.Completed
        assertEquals(HostedSemanticRead.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), result.value)
        assertEquals(0, providerCalls)
        assertEquals(ExecutionBudgetPresence.Absent, result.executionBudget)
        assertInstanceOf(HostedSemanticBudgetObservation.Exhausted::class.java, receipts.single().semanticBudget)
        executor.retire()
        executor.drain()
    }

    @Test
    fun `final semantic and diagnostic allowances use time after publication refinement`() = runTest {
        var now = 0L
        val executor = HostedQueryExecutor(backgroundScope, { now })
        val result =
            executor.execute(
                executor.endpoint,
                shortHostLimits(),
                publication =
                    HostedReadPublicationAdmission { report, limits ->
                        now += 100_000_000L
                        HostedReadPublicationAdmission.Containment.admit(report, limits)
                    },
            ) { progress ->
                now = 3_000_000_000L
                runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { it }
            } as HostedExecution.Completed
        val allowance = (result.value as HostedSemanticRead.Resolved).evidence
        assertEquals(550L, allowance.semantic.value)
        assertEquals(550L, allowance.diagnosticScope.value)
        assertEquals(
            ExecutionBudgetPresence.Present(ExecutionBudgetReport.from(allowance.executionBudget)),
            result.executionBudget,
        )
        executor.retire()
        executor.drain()
    }
}
