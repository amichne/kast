package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnerControlledImpactBudgetTest {
    @Test
    fun `runner impact report keeps output grants without semantic limits`() {
        val results = ResultLimit.parse(1).impactFixture()
        val bytes = ReturnedByteLimit.parse(2000).impactFixture()
        val budget = ImpactFlowBudgetDocument.from(ResourceBudget.RunnerControlled, results, bytes)
        val json = Json { encodeDefaults = true }
        val actual = json.encodeToString(ImpactFlowBudgetDocument.serializer(), budget)
        val expected = json.encodeToString(ExpectedImpactBudget.serializer(), ExpectedImpactBudget())
        assertEquals(json.parseToJsonElement(expected), json.parseToJsonElement(actual))
        assertEquals(budget, json.decodeFromString(ImpactFlowBudgetDocument.serializer(), expected))
        assertTrue(budget.permitsWork(2))
        assertTrue(budget.permitsElapsedMillis(2))
    }

    @Test
    fun `runner child cannot derive unlimited authority from bounded parent`() {
        val results = ResultLimit.parse(1).impactFixture()
        val bytes = ReturnedByteLimit.parse(2000).impactFixture()
        val finite =
            ImpactFlowBudgetDocument(
                ElapsedTimeLimitMillis.parse(1).impactFixture(),
                WorkUnitLimit.parse(1).impactFixture(),
                results,
                bytes,
            )
        val runner = ImpactFlowBudgetDocument.RunnerControlled(results, bytes)
        assertFalse(finite.permitsWork(2))
        assertFalse(finite.permitsElapsedMillis(2))
        assertTrue(finite.isWithin(runner))
        assertFalse(runner.isWithin(finite))
        assertTrue(runner.isWithin(runner))
    }
}

@Serializable
private data class ExpectedImpactBudget(
    val mode: String = "RUNNER_CONTROLLED",
    val maxResults: Int = 1,
    val maxReturnedBytes: Long = 2000,
)

private fun <Value> Refinement<Value, *>.impactFixture(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid impact fixture")
    }
