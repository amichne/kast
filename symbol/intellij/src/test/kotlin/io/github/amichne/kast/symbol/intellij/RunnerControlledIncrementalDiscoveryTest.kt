package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRemainder
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunnerControlledIncrementalDiscoveryTest {
    @Test
    fun `runner discovery pages retain oversized facts beyond internal file and byte limits`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture =
                fixture(
                    project,
                    home.resolve("runner"),
                    mapOf(
                        "First.kt" to "fun first() = 1",
                        "Second.kt" to "fun second() = 2",
                    ),
                )
            val base = fixture.request(1, 1, 1, null)
            val budget = runnerBudget()
            val limits = runnerLimits()
            val names = mutableListOf<String>()
            var request = base.withRunnerBudget(budget)
            var now = 0L
            val clock = IntellijReadNanoClock {
                now += 1_000_000_000_000L
                now
            }
            var exhausted = false
            repeat(4) {
                if (!exhausted) {
                    val outcome = fixture.runnerPage(request, limits, clock)
                    assertTrue(outcome.batch().candidates.size <= 1)
                    if (outcome.batch().candidates.isNotEmpty()) assertTrue(outcome.batch().encodedBytes.value > 1)
                    names += outcome.batch().candidates.map { it.name.value }
                    when (val progress = outcome.progress) {
                        SymbolDiscoveryProgress.Exhausted -> exhausted = true
                        is SymbolDiscoveryProgress.Resumable ->
                            request = base.withRunnerBudget(budget, progress.remainder)
                        is SymbolDiscoveryProgress.Blocked ->
                            assertInstanceOf(SymbolDiscoveryProgress.Resumable::class.java, progress)
                    }
                }
            }
            assertTrue(exhausted)
            assertEquals(listOf("first", "second"), names)
        }
    }
}

private fun runnerBudget() =
    SymbolDiscoveryBudget(
        ResourceBudget.RunnerControlled,
        SymbolDiscoveryByteLimit.parse(1).incrementalFixture(),
        ResultLimit.parse(1).incrementalFixture(),
    )

private fun runnerLimits() =
    ReadLimits.resolve(
            environment =
                mapOf(
                    "KAST_READ_DISCOVERY_FILES" to "1",
                    "KAST_READ_QUERY_CHECKPOINT_BYTES" to "1",
                )
        )
        .incrementalFixture()

private fun SymbolDiscoveryRequest.withRunnerBudget(
    budget: SymbolDiscoveryBudget,
    remainder: SymbolDiscoveryRemainder? = null,
) =
    SymbolDiscoveryRequest(
        scope = scope,
        target = SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.SYMBOL),
        budget = budget,
        constraints = constraints,
        remainder = remainder,
    )

private fun IntellijDeclarationDiscoveryTestFixture.Fixture.runnerPage(
    request: SymbolDiscoveryRequest,
    limits: ReadLimits,
    clock: IntellijReadNanoClock,
): SymbolDiscoveryOutcome =
    ApplicationManager.getApplication()
        .runReadAction<IntellijNativeDiscoveryExecution> {
            IntellijIncrementalDeclarationDiscovery(
                    scope = scope,
                    request = request,
                    limits = limits,
                    allowance = IntellijDeclarationDiscoveryAllowance(request, clock),
                    initialPartitions = this::initialPartitions,
                    observePartition = this::observePartition,
                    environment = { IntellijDiscoveryEnvironmentState.READY },
                    cancellationCheck = {},
                )
                .execute()
        }
        .outcome()

private fun <Value> Refinement<Value, *>.incrementalFixture(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid incremental fixture")
    }
