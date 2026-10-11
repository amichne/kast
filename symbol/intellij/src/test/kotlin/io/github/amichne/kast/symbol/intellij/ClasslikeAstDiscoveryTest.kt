package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.Controls
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.denseSources
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.discover
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withUnsupportedParser
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real dispatcher, parser, admission and projection; only native indexes and clock are supplied. */
class ClasslikeAstDiscoveryTest {
    @Test
    fun `dense dispatcher materializes only bounded declaration and projection PSI`(@TempDir home: Path) =
        withParser(home) { project ->
            val source = fixture(project, home.resolve("source"), denseSources(), setOf(CompilerSymbolKind.CLASSLIKE))
            val before = ClasslikeAstPsiWork.census(source.files)
            val result = discover(project, source, "Target", controls = Controls(work = 1))
            val after = ClasslikeAstPsiWork.census(source.files)
            assertTrue(result.outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(listOf("Target"), result.outcome.batch().candidates.map { it.name.value })
            assertEquals(18_063, before.nodes)
            assertEquals(before.nodes, after.nodes)
            val created = after.cachedCompositePsi - before.cachedCompositePsi
            println(
                "CLASSLIKE_PIPELINE_WORK nodes=${after.nodes} newCompositePsi=$created " +
                    "candidateBytes=${result.outcome.batch().encodedBytes.value} counters=${result.observation.counters}"
            )
            assertTrue(
                created <= 32,
                "CLASSLIKE_PIPELINE_PSI: expected <=32 new composite PSI wrappers, observed $created",
            )
        }

    @Test
    fun `dense reference wrapper retains complete discovery without spending excluded names`(@TempDir home: Path) =
        withParser(home) { project ->
            val source = fixture(project, home.resolve("source"), denseSources(), setOf(CompilerSymbolKind.CLASSLIKE))
            val result = discover(project, source, "Target", controls = Controls(work = 1))
            assertTrue(result.outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(listOf("Target"), result.outcome.batch().candidates.map { it.name.value })
            assertEquals(1L, result.outcome.batch().examinedWorkUnits.value)
            assertEquals(18_063, result.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED])
            assertEquals(2, result.observation.counters[IntellijReadCounter.DECLARATION_AST_PSI_REQUESTS])
        }

    @Test
    fun `recovered enum stays discoverable without any class introducer`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf("Input.kt" to "enum Target { ENTRY }"),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            val result = discover(project, source, "Target", controls = Controls(indexed = false))
            assertEquals(listOf("Target"), result.outcome.batch().candidates.map { it.name.value })
            assertTrue(result.outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(1L, result.outcome.batch().examinedWorkUnits.value)
        }

    @Test
    fun `unsupported parser and normal dispatcher preserve local body candidates on warm PSI`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf(
                        "Input.kt" to
                            "class Target\nval property = run { class Target; 0 }\nfun outer() { class Target }"
                    ),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            val baseline = withUnsupportedParser {
                discover(project, source, "Target", controls = Controls(indexed = false)).outcome
            }
            val candidate = discover(project, source, "Target", controls = Controls(indexed = false)).outcome
            assertTrue(candidate is SymbolDiscoveryOutcome.Complete)
            assertEquals(3, candidate.batch().candidates.size)
            assertEquals(baseline.batch().candidates, candidate.batch().candidates)
            assertEquals(baseline.batch().examinedWorkUnits, candidate.batch().examinedWorkUnits)
        }
}
