package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.Controls
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.discover
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withUnsupportedParser
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/** Candidate runs first on a new physical file per requested name, without a pre-expanding tree census. */
class ClasslikeAstColdTraversalTest {
    @TestFactory
    fun `cold recovered lazy error and nested declarations retain complete dispatcher outcomes`(
        @TempDir home: Path
    ): List<DynamicTest> =
        listOf("enum recovery", "function body", "error body", "constructor and init").mapIndexed { index, label ->
            val case = ClasslikeAstDifferentialCases.values.single { it.label == label }
            DynamicTest.dynamicTest("cold $label") {
                withParser(home.resolve("parser-$index")) { project ->
                    case.names.filterNotNull().distinct().forEachIndexed { query, name ->
                        val source =
                            fixture(
                                project,
                                home.resolve("source-$index-$query"),
                                mapOf("Input.kt" to case.source),
                                setOf(CompilerSymbolKind.CLASSLIKE),
                            )
                        assertFalse(source.files.single().isContentsLoaded, "candidate must precede AST expansion")
                        val candidate = discover(project, source, name, Controls(indexed = false))
                        assertTrue(candidate.outcome is SymbolDiscoveryOutcome.Complete)
                        assertEquals(
                            List(case.names.count { it == name }) { name },
                            candidate.outcome.batch().candidates.map { it.name.value },
                        )
                        assertTrue(
                            (candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0) > 0
                        )
                        assertTrue(
                            (candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_PSI_FALLBACKS] ?: 0) > 0
                        )
                        val baseline = withUnsupportedParser {
                            discover(project, source, name, Controls(indexed = false))
                        }
                        assertEquals(baseline.outcome, candidate.outcome)
                        assertEquals(
                            baseline.observation.counters[IntellijReadCounter.DECLARATION_PSI_NODES_VISITED],
                            candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED],
                        )
                    }
                }
            }
        }
}
