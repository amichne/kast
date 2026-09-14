package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedContainmentReportTest {
    @Test
    fun `freshness witness bounds every closed freshness failure at every publication stage`() {
        val report =
            ExecutionBudgetReport.from(
                (HostedSemanticTimeAllowance.admit(ReadLimits.Default, 100L) as Refinement.Refined)
                    .value
                    .executionBudget
            )
        val budget = ExecutionBudgetPresence.Present(report)
        fun bytes(failure: HostedQueryFailure) =
            HostedQueryStage.entries.maxOf { stage ->
                HostedQueryWire.encode(HostedQueryResult.Rejected(failure, stage, budget))
                    .toByteArray(Charsets.UTF_8)
                    .size
            }
        val witness =
            bytes(
                HostedQueryFailure.Freshness(
                    VfsPassiveReadAdmissionFailure.Unavailable(VfsPassiveReadUnavailableCause.GradleModelUnavailable)
                )
            )
        val unavailable =
            VfsPassiveReadUnavailableCause::class.sealedSubclasses.flatMap { type ->
                type.objectInstance?.let { listOf(it) }
                    ?: when (type) {
                        VfsPassiveReadUnavailableCause.ObservationFailed::class ->
                            ProjectReadEpochObservationStage.entries.map {
                                VfsPassiveReadUnavailableCause.ObservationFailed(it)
                            }
                        else -> error("New unavailable variant requires publication capacity proof")
                    }
            }
        val failures =
            VfsPassiveReadAdmissionFailure::class.sealedSubclasses.flatMap { type ->
                type.objectInstance?.let { listOf(it) }
                    ?: when (type) {
                        VfsPassiveReadAdmissionFailure.Unavailable::class ->
                            unavailable.map {
                                VfsPassiveReadAdmissionFailure.Unavailable(it)
                            }
                        else -> error("New freshness variant requires publication capacity proof")
                    }
            }
        for (failure in failures) assertTrue(
            bytes(HostedQueryFailure.Freshness(failure)) <= witness,
            failure.toString(),
        )
    }

    @Test
    fun `minimum host frame rejects the candidate before grant recording and provider access`() = runTest {
        val limits =
            (ReadLimits.resolve(
                    environment =
                        mapOf(
                            "KAST_READ_HOST_RESPONSE_BYTES" to "256",
                            "KAST_READ_SEMANTIC_RETURNED_BYTES" to "256",
                            "KAST_READ_SOURCE_RETURNED_BYTES" to "256",
                        )
                ) as Refinement.Refined)
                .value
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { 0L }) { policy ->
                HostedReadDiagnostics({ 0L }, policy, receipts::add)
            }
        var providerCalls = 0
        val result =
            executor.execute(executor.endpoint, limits) { progress ->
                runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { providerCalls += 1 }
            } as HostedExecution.Completed
        assertEquals(HostedSemanticRead.Rejected(HostedQueryFailure.RESULT_LIMIT_EXCEEDED), result.value)
        assertEquals(0, providerCalls)
        assertEquals(ExecutionBudgetPresence.Absent, result.executionBudget)
        val observation = receipts.single().semanticBudget as HostedSemanticBudgetObservation.PublicationRejected
        assertEquals(256L, observation.candidate.returnedBytes.effective.value)
        val encoded =
            HostedQueryWire.encode(
                HostedQueryResult.Rejected(
                    HostedQueryFailure.RESULT_LIMIT_EXCEEDED,
                    result.stage,
                    result.executionBudget,
                )
            )
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 256)
        assertFalse("execution_budget" in Json.parseToJsonElement(encoded).jsonObject)
        executor.retire()
        executor.drain()
    }

    @Test
    fun `hard timeout preserves an actual semantic grant and never invents one before admission`() = runTest {
        for (admit in listOf(false, true)) {
            val executor = HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000L })
            var expected: ExecutionBudgetPresence = ExecutionBudgetPresence.Absent
            val result =
                executor.execute(executor.endpoint) { progress ->
                    if (admit) {
                        runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { allowance ->
                            expected =
                                ExecutionBudgetPresence.Present(ExecutionBudgetReport.from(allowance.executionBudget))
                            awaitCancellation()
                        }
                    } else awaitCancellation()
                } as HostedExecution.Rejected
            assertEquals(HostedQueryFailure.BUDGET_EXCEEDED, result.failure)
            assertEquals(expected, result.executionBudget)
            val encoded =
                Json.parseToJsonElement(
                        HostedQueryWire.encode(
                            HostedQueryResult.Rejected(result.failure, result.stage, result.executionBudget)
                        )
                    )
                    .jsonObject
            assertEquals("BUDGET_EXCEEDED", encoded.getValue("failure").jsonPrimitive.content)
            assertEquals(result.stage.name, encoded.getValue("stage").jsonPrimitive.content)
            when (expected) {
                ExecutionBudgetPresence.Absent -> assertFalse("execution_budget" in encoded)
                is ExecutionBudgetPresence.Present ->
                    assertEquals(
                        Json.encodeToJsonElement(ExecutionBudgetReport.serializer(), expected.report),
                        encoded["execution_budget"],
                    )
            }
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `post evaluation freshness rejection retains the same admitted grant`() = runTest {
        val executor = HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000L })
        var validations = 0
        lateinit var report: ExecutionBudgetReport
        val result =
            executor.execute(executor.endpoint) { progress ->
                runHostedReadTransaction(
                    progress,
                    {
                        if (validations++ == 0) Refinement.Refined(Unit)
                        else Refinement.Rejected(HostedQueryFailure.CONTENT_MOVED)
                    },
                ) { allowance ->
                    report = ExecutionBudgetReport.from(allowance.executionBudget)
                    42
                }
            } as HostedExecution.Completed
        assertEquals(HostedSemanticRead.Rejected(HostedQueryFailure.CONTENT_MOVED), result.value)
        assertEquals(ExecutionBudgetPresence.Present(report), result.executionBudget)
        assertEquals(HostedQueryStage.CONTENT_REVALIDATION, result.stage)
        executor.retire()
        executor.drain()
    }
}
