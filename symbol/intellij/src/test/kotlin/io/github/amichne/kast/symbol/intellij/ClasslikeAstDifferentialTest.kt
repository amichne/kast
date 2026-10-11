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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class ClasslikeAstDifferentialTest {
    @TestFactory
    fun `all parser cases retain real dispatcher identity order work bytes and coverage`(
        @TempDir home: Path
    ): List<DynamicTest> =
        ClasslikeAstDifferentialCases.values.mapIndexed { index, case ->
            DynamicTest.dynamicTest(case.label) {
                withParser(home.resolve("case-$index")) { project ->
                    val source =
                        fixture(
                            project,
                            home.resolve("source-$index"),
                            mapOf("Input.kt" to case.source),
                            setOf(CompilerSymbolKind.CLASSLIKE),
                        )
                    for (name in case.names.filterNotNull().distinct()) {
                        val baseline = withUnsupportedParser {
                            discover(project, source, name, Controls(indexed = false))
                        }
                        val candidate = discover(project, source, name, Controls(indexed = false))
                        assertTrue(candidate.outcome is SymbolDiscoveryOutcome.Complete)
                        assertEquals(
                            List(case.names.count { it == name }) { name },
                            candidate.outcome.batch().candidates.map { it.name.value },
                        )
                        assertEquals(baseline.outcome, candidate.outcome)
                        assertEquals(
                            baseline.observation.counters[IntellijReadCounter.DECLARATION_PSI_NODES_VISITED],
                            candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED],
                        )
                        assertEquals(
                            1,
                            baseline.observation.counters[IntellijReadCounter.DECLARATION_AST_LEGACY_FALLBACKS],
                        )
                        if (case.label in setOf("enum recovery", "function body", "property body", "error body")) {
                            assertTrue(
                                (candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_PSI_FALLBACKS]
                                    ?: 0) > 0,
                                "lazy/error mapping must retain PSI fallback for ${case.label}",
                            )
                        }
                    }
                }
            }
        }
}
