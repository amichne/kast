package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.ResolvedSymbol
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryDeadlineAdmissionTest {
    @Test
    fun `deadline boundaries preserve unstarted exact references`() = runTest {
        for (cutoff in 1..MAXIMUM_CLOCK_READS) verifyDeadline(cutoff, Stage.REFERENCE)
    }

    @Test
    fun `deadline boundaries preserve unstarted discovery and refinement`() = runTest {
        for (cutoff in 1..MAXIMUM_CLOCK_READS) verifyDeadline(cutoff, Stage.INSPECT)
    }

    @Test
    fun `deadline boundaries preserve unstarted visibility reads`() = runTest {
        for (cutoff in 1..MAXIMUM_CLOCK_READS) verifyDeadline(cutoff, Stage.VISIBILITY)
    }

    private suspend fun verifyDeadline(cutoff: Int, stage: Stage) {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val clock = DeadlineClock(cutoff)
            val effects = mutableListOf<Stage>()
            val service =
                service(
                    discovery = discoveryWithCandidate(),
                    exact =
                        exactOperations(
                            describe = {
                                effects += Stage.REFERENCE
                                SymbolDescriptionResult.Described(SymbolDescription.from(it))
                            },
                            resolve = {
                                effects += Stage.INSPECT
                                SymbolResolutionResult.Resolved(ResolvedSymbol(selector(it)))
                            },
                        ),
                    source =
                        SourceReadOperations {
                            effects += Stage.VISIBILITY
                            selfRead(selected, DeclarationVisibility.PUBLIC)
                        },
                    clock = clock,
                )
            val plan =
                when (stage) {
                    Stage.REFERENCE -> exactReferencePlan(List(2) { selected })
                    Stage.INSPECT -> symbolPlan()
                    Stage.VISIBILITY -> visibilityPlan(selected)
                }
            val input = request(plan, workLimit = 8L, elapsedMillis = 1L)
            val first = service.run(input)
            val expected = if (stage == Stage.REFERENCE) 2 else 1
            assertPageCanFinish(first, Replay(input, service, clock, expected, effects), cutoff)
        }
    }

    private data class Replay(
        val input: QueryExecutionRequest,
        val service: QueryService,
        val clock: DeadlineClock,
        val expected: Int,
        val effects: List<Stage>,
    )

    private suspend fun QueryServiceTest.assertPageCanFinish(
        first: QueryExecutionResult,
        replay: Replay,
        cutoff: Int,
    ) =
        with(replay) {
            when (first) {
                is QueryExecutionResult.Complete -> assertEquals(expected, first.symbolCount(), "clock read $cutoff")
                is QueryExecutionResult.Rejected -> error("Unexpected rejection ${first.reason}")
                is QueryExecutionResult.Qualified ->
                    when (val continuation = first.continuation) {
                        is QueryContinuationState.Terminal -> {
                            assertEquals(QueryTerminalReason.NO_PROGRESS, continuation.reason, "clock read $cutoff")
                            assertEquals(emptyList<Stage>(), effects)
                        }
                        is QueryContinuationState.Resumable -> {
                            clock.resume()
                            val request =
                                QueryExecutionRequest.create(
                                        input.plan,
                                        input.lease,
                                        input.budget,
                                        continuation.checkpoint,
                                    )
                                    .refined()
                            val last = service.run(request)
                            assertEquals(expected, first.symbolCount() + last.symbolCount(), "clock read $cutoff")
                            assertEquals(expected, effects.count { it != Stage.VISIBILITY })
                        }
                    }
            }
        }

    private enum class Stage {
        REFERENCE,
        INSPECT,
        VISIBILITY,
    }

    private class DeadlineClock(private val cutoff: Int) : QueryNanoClock {
        private var reads = 0
        private var resumed = false

        override fun now(): Long = if (resumed || reads++ < cutoff) 0L else NANOS_PER_MILLISECOND

        fun resume() {
            resumed = true
        }
    }

    private companion object {
        const val MAXIMUM_CLOCK_READS = 24
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejected: $failure")
    }
