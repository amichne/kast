package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnerControlledSymbolDiscoveryTest {
    @Test
    fun `runner discovery completes beyond bounded time work result and internal byte grants`() {
        val scenario =
            SymbolDiscoveryTest()
                .fixture(
                    resultLimit = 1,
                    returnedBytes = 1,
                    workLimit = 1,
                    elapsedMillis = 1,
                    clock = IntellijDiscoveryStepClock(step = 1_000_000L),
                )
        val runner =
            SymbolDiscoveryRequest(
                scope = scenario.request.scope,
                target =
                    SymbolDiscoveryTarget.Name(
                        SymbolNameDiscoveryKind.SYMBOL,
                        SymbolDiscoveryPattern.parse("Item").runnerFixture(),
                        SymbolDiscoveryMatch.FUZZY,
                    ),
                budget =
                    SymbolDiscoveryBudget(
                        ResourceBudget.RunnerControlled,
                        SymbolDiscoveryByteLimit.parse(1).runnerFixture(),
                        ResultLimit.parse(1).runnerFixture(),
                    ),
                constraints = scenario.request.constraints,
            )
        val execution = scenario.query.discover(scenario.compiledScope, runner, listOf(scenario.contributor))
        val produced = assertInstanceOf(IntellijNativeDiscoveryExecution.Produced::class.java, execution)
        val outcome = assertInstanceOf(SymbolDiscoveryOutcome.Complete::class.java, produced.outcome)
        assertEquals(listOf("AItem", "ZItem"), outcome.batch.candidates.map { it.name.value })
        assertTrue(outcome.batch.encodedBytes.value > 1)
        assertTrue(outcome.batch.examinedWorkUnits.value > 1)
    }
}

private fun <Value> Refinement<Value, *>.runnerFixture(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid runner discovery fixture")
    }
