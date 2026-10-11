package io.github.amichne.kast.symbol.intellij

import com.sun.management.ThreadMXBean
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.Controls
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.denseSources
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.discover
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withUnsupportedParser
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import java.lang.management.ManagementFactory
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Allocation observations are diagnostic only; deterministic tree/output assertions are the gate. */
class ClasslikeAstAllocationDiagnosticTest {
    @Test
    fun `paired cold PSI pipelines expose qualification overhead and warm metadata work`(@TempDir home: Path) =
        withParser(home) { project ->
            repeat(3) { attempt ->
                val legacy =
                    fixture(
                        project,
                        home.resolve("legacy-$attempt"),
                        denseSources(),
                        setOf(CompilerSymbolKind.CLASSLIKE),
                    )
                val current =
                    fixture(
                        project,
                        home.resolve("astnew-$attempt"),
                        denseSources(),
                        setOf(CompilerSymbolKind.CLASSLIKE),
                    )
                val legacyBefore = ClasslikeAstPsiWork.census(legacy.files)
                val currentBefore = ClasslikeAstPsiWork.census(current.files)
                val baseline = withUnsupportedParser {
                    allocated { discover(project, legacy, "Target", Controls(work = 1)) }
                }
                val candidate = allocated { discover(project, current, "Target", Controls(work = 1)) }
                val legacyAfter = ClasslikeAstPsiWork.census(legacy.files)
                val currentAfter = ClasslikeAstPsiWork.census(current.files)
                assertTrue(baseline.value.outcome is SymbolDiscoveryOutcome.Complete)
                assertTrue(candidate.value.outcome is SymbolDiscoveryOutcome.Complete)
                assertEquals(listOf("Target"), candidate.value.outcome.batch().candidates.map { it.name.value })
                assertEquals(baseline.value.outcome.batch().encodedBytes, candidate.value.outcome.batch().encodedBytes)
                assertEquals(
                    baseline.value.outcome.batch().examinedWorkUnits,
                    candidate.value.outcome.batch().examinedWorkUnits,
                )
                assertEquals(18_063, legacyAfter.nodes)
                assertEquals(legacyAfter.nodes, currentAfter.nodes)
                val legacyWrappers = legacyAfter.cachedCompositePsi - legacyBefore.cachedCompositePsi
                val currentWrappers = currentAfter.cachedCompositePsi - currentBefore.cachedCompositePsi
                assertEquals(6_025, legacyWrappers)
                assertTrue(currentWrappers <= 32)
                println(
                    "CLASSLIKE_ALLOCATION attempt=$attempt nodes=${currentAfter.nodes} " +
                        "legacyNewPsi=$legacyWrappers currentNewPsi=$currentWrappers " +
                        "legacyAllocated=${baseline.bytes} currentAllocated=${candidate.bytes} " +
                        "candidateBytes=${candidate.value.outcome.batch().encodedBytes.value} " +
                        "counters=${candidate.value.observation.counters}"
                )
            }
        }

    private data class Allocated<Value>(val value: Value, val bytes: Long?)

    private fun <Value> allocated(action: () -> Value): Allocated<Value> {
        val counter =
            (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf {
                it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled
            }
        val thread = Thread.currentThread().threadId()
        val before = counter?.getThreadAllocatedBytes(thread)
        val value = action()
        val after = counter?.getThreadAllocatedBytes(thread)
        return Allocated(value, if (before != null && after != null) after - before else null)
    }
}
