package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.exhaust
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Physical PSI establishes discovery traversal, not K2 identity or real index scheduling. */
class IntellijIncrementalDeclarationDiscoveryTest {
    @Test
    fun `all limits one five and twenty exhaust exact independently specified finite declarations`(
        @TempDir home: Path
    ) {
        withParser(home) { project ->
            val text =
                """
                package sample
                class Container(val constructorProperty: Int = 0) {
                    fun member() = 1
                    val property = 2
                    class Nested
                }
                typealias Alias = Container
                fun outer() { fun local() = 1; local() }
                """
                    .trimIndent()
            val expected =
                listOf("Container", "constructorProperty", "member", "property", "Nested", "Alias", "outer", "local")
            val fixture = fixture(project, home.resolve("finite"), mapOf("Declarations.kt" to text))
            for (limit in listOf(1, 5, 20)) {
                val startingCollected = fixture.counters[IntellijReadCounter.CANDIDATES_COLLECTED] ?: 0
                val startingProjected = fixture.counters[IntellijReadCounter.CANDIDATES_PROJECTED] ?: 0
                val exhausted = exhaust(fixture, limit, 1_000)
                assertEquals(expected, exhausted.names, "limit $limit preserves file/source order")
                assertEquals(expected.size, exhausted.names.toSet().size)
                assertEquals(
                    expected.size,
                    fixture.counters[IntellijReadCounter.CANDIDATES_COLLECTED]!! - startingCollected,
                )
                assertEquals(
                    expected.size,
                    fixture.counters[IntellijReadCounter.CANDIDATES_PROJECTED]!! - startingProjected,
                )
                assertEquals(1, exhausted.inventories, "the inventory is established once")
                assertEquals(fixture.leafCount, exhausted.leaves, "successors do not replay consumed PSI leaves")
            }
        }
    }

    @Test
    fun `large single file advances direct source positions under bounded grants`(@TempDir home: Path) {
        withParser(home) { project ->
            val expected = List(400) { "function$it" }
            val fixture =
                fixture(
                    project,
                    home.resolve("large"),
                    mapOf("Large.kt" to expected.joinToString("\n") { "fun $it() = 1" }),
                )
            val exhausted = exhaust(fixture, 20, 300)
            assertEquals(expected, exhausted.names)
            assertEquals(1, exhausted.inventories)
            assertEquals(fixture.leafCount, exhausted.leaves)
            assertTrue(exhausted.pages.all { it.batch().examinedWorkUnits.value <= 300 })
            assertTrue(exhausted.pages.first().batch().candidates.isNotEmpty())
        }
    }

    @Test
    fun `multi file scope advances while excluded declaration containers retain eligible members`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture =
                fixture(
                    project,
                    home.resolve("broad"),
                    (0 until 600).associate { index ->
                        "File${index.toString().padStart(3, '0')}.kt" to
                            "class Container$index { fun function$index() = 1 }"
                    },
                    setOf(CompilerSymbolKind.FUNCTION),
                )
            val exhausted = exhaust(fixture, 5, 50)
            assertEquals(List(600) { "function$it" }, exhausted.names)
            assertEquals(1, exhausted.inventories)
            assertEquals(fixture.leafCount, exhausted.leaves)
        }
    }

    @Test
    fun `a grant exhausted during partition discovery preserves undiscovered files`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("inventory"), mapOf("A.kt" to "class A", "B.kt" to "class B"))
            val result = fixture.page(20, 1, null).outcome()
            assertTrue(result.progress is SymbolDiscoveryProgress.Resumable)
            val retained = (result.progress as SymbolDiscoveryProgress.Resumable).remainder
            assertEquals(2, retained.frontier.size)
            assertEquals(0L, retained.completedFiles.value)
            assertTrue(result.batch().candidates.isEmpty())
            assertTrue(fixture.counters.isEmpty(), "partition progress has not reached any declaration leaf")
        }
    }

    @Test
    fun `lexical partition order handles files preceding descendants of an earlier directory`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture =
                fixture(
                    project,
                    home.resolve("lexical"),
                    linkedMapOf(
                        "a/Z.kt" to "class Descendant",
                        "z.kt" to "class Last",
                        "a.kt" to "class Dot",
                    ),
                )
            val exhausted = exhaust(fixture, 1, 20)
            assertEquals(listOf("Dot", "Descendant", "Last"), exhausted.names)
            assertEquals(2, exhausted.inventories)
            assertEquals(fixture.leafCount, exhausted.leaves)
        }
    }

    @Test
    fun `a byte grant smaller than one detached candidate terminates with the exact cause`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("bytes"), mapOf("A.kt" to "class A"))
            val result = fixture.page(1, 100, null, 1).outcome()
            assertEquals(SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.ITEM_BYTE_LIMIT), result.progress)
            assertTrue(result.batch().candidates.isEmpty())
            assertEquals(1, fixture.counters[IntellijReadCounter.CANDIDATES_COLLECTED])
            assertEquals(null, fixture.counters[IntellijReadCounter.CANDIDATES_PROJECTED])
        }
    }

    @Test
    fun `deadline before the first partition returns finite evidence without an unusable successor`(
        @TempDir home: Path
    ) {
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("expired"), mapOf("A.kt" to "class A"))
            var started = false
            val clock = IntellijReadNanoClock {
                if (started) 10_000_000_000L
                else {
                    started = true
                    0L
                }
            }
            val result = fixture.page(1, 100, null, clock = clock).outcome()
            assertEquals(
                SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.INSUFFICIENT_EXECUTION_GRANT),
                result.progress,
            )
            assertTrue(result.batch().candidates.isEmpty())
            assertEquals(0, result.batch().measurements.examinedLeaves.value)
            assertEquals(0, fixture.initialInventories, "expired work cannot enter root inventory")
        }
    }

    @Test
    fun `cancellation before discovery cannot enter root inventory`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("cancelled"), mapOf("A.kt" to "class A"))
            assertThrows(ProcessCanceledException::class.java) {
                fixture.page(1, 100, null, cancellationCheck = { throw ProcessCanceledException() })
            }
            assertEquals(0, fixture.initialInventories)
            assertEquals(0, fixture.inventories)
        }
    }

    private companion object {
        fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error(failure.toString())
            }
    }
}
