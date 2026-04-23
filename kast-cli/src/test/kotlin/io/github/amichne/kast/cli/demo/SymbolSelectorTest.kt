package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.cli.DemoTextMatchCategory
import io.github.amichne.kast.cli.DemoTextSearchSummary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SymbolSelectorTest {

    @Test
    fun `returns first symbol that clears all thresholds`() {
        val target = symbol("io.acme.demo.execute", visibility = SymbolVisibility.PUBLIC)
        val other = symbol("io.acme.demo.helperRoutine", visibility = SymbolVisibility.PUBLIC)
        val selector = SymbolSelector()

        val outcome = runBlocking {
            selector.select(
                candidates = listOf(other, target),
                probe = staticProbe(
                    target to evidence(grepHits = 30, refFiles = listOf("A.kt", "B.kt", "C.kt", "C.kt", "D.kt", "D.kt", "E.kt")),
                    other to evidence(grepHits = 5, refFiles = listOf("A.kt")),
                ),
            )
        }

        outcome as SelectionOutcome.Found
        assertEquals(target.fqName, outcome.symbol.fqName)
        assertEquals(7, outcome.evidence.resolvedRefCount)
        assertEquals(30, outcome.evidence.grepHitCount)
    }

    @Test
    fun `noisy named symbol is preferred over a longer name even if listed second`() {
        val noisy = symbol("io.acme.demo.id")
        val longer = symbol("io.acme.demo.executeMigration")
        val selector = SymbolSelector()

        val outcome = runBlocking {
            selector.select(
                candidates = listOf(longer, noisy),
                probe = staticProbe(
                    noisy to evidence(grepHits = 80, refFiles = listOf("A.kt", "B.kt", "C.kt", "D.kt", "E.kt", "F.kt", "G.kt", "H.kt")),
                    longer to evidence(grepHits = 80, refFiles = listOf("A.kt", "B.kt", "C.kt", "D.kt", "E.kt", "F.kt", "G.kt", "H.kt")),
                ),
            )
        }

        outcome as SelectionOutcome.Found
        assertEquals(noisy.fqName, outcome.symbol.fqName)
    }

    @Test
    fun `skips candidate whose refs do not span at least two files`() {
        val singleFile = symbol("io.acme.demo.alpha")
        val multiFile = symbol("io.acme.demo.beta")
        val selector = SymbolSelector()

        val outcome = runBlocking {
            selector.select(
                candidates = listOf(singleFile, multiFile),
                probe = staticProbe(
                    singleFile to evidence(grepHits = 50, refFiles = List(10) { "OnlyOne.kt" }),
                    multiFile to evidence(grepHits = 30, refFiles = listOf("A.kt", "B.kt", "C.kt", "D.kt", "E.kt", "F.kt")),
                ),
            )
        }

        outcome as SelectionOutcome.Found
        assertEquals(multiFile.fqName, outcome.symbol.fqName)
    }

    @Test
    fun `skips candidate whose noise ratio falls below the threshold`() {
        val tooClean = symbol("io.acme.demo.clear")
        val noisy = symbol("io.acme.demo.id")
        val selector = SymbolSelector(DemoSelectionConfig(minRefs = 5, noiseRatio = 2.0))

        val outcome = runBlocking {
            selector.select(
                candidates = listOf(tooClean, noisy),
                probe = staticProbe(
                    tooClean to evidence(grepHits = 6, refFiles = listOf("A.kt", "B.kt", "C.kt", "D.kt", "E.kt", "F.kt")),
                    noisy to evidence(grepHits = 30, refFiles = listOf("A.kt", "B.kt", "C.kt", "D.kt", "E.kt", "F.kt")),
                ),
            )
        }

        outcome as SelectionOutcome.Found
        assertEquals(noisy.fqName, outcome.symbol.fqName)
    }

    @Test
    fun `excludes private and local symbols up front`() {
        val privateSym = symbol("io.acme.demo.hidden", visibility = SymbolVisibility.PRIVATE)
        val localSym = symbol("io.acme.demo.tmp", visibility = SymbolVisibility.LOCAL)
        val selector = SymbolSelector()

        val seen = mutableListOf<String>()
        val outcome = runBlocking {
            selector.select(
                candidates = listOf(privateSym, localSym),
                probe = SymbolProbe { sym ->
                    seen.add(sym.fqName)
                    evidence(grepHits = 100, refFiles = listOf("A.kt", "B.kt"))
                },
            )
        }

        assertTrue(seen.isEmpty(), "private/local symbols must not be probed; saw $seen")
        outcome as SelectionOutcome.NoQualifyingSymbol
        assertEquals(0, outcome.candidatesInspected)
    }

    @Test
    fun `returns NoQualifyingSymbol when nothing clears the bar`() {
        val a = symbol("io.acme.demo.alpha")
        val b = symbol("io.acme.demo.beta")
        val selector = SymbolSelector(DemoSelectionConfig(minRefs = 5, noiseRatio = 2.0))

        val outcome = runBlocking {
            selector.select(
                candidates = listOf(a, b),
                probe = staticProbe(
                    a to evidence(grepHits = 0, refFiles = listOf("A.kt", "B.kt")),
                    b to evidence(grepHits = 4, refFiles = listOf("A.kt")),
                ),
            )
        }

        outcome as SelectionOutcome.NoQualifyingSymbol
        assertEquals(2, outcome.candidatesInspected)
        assertTrue(outcome.reason.contains("min-refs"))
    }

    @Test
    fun `caps inspection at maxCandidates`() {
        val pool = (1..50).map { symbol("io.acme.demo.sym$it") }
        val seen = mutableListOf<String>()
        val selector = SymbolSelector(DemoSelectionConfig(minRefs = 5, noiseRatio = 2.0, maxCandidates = 3))

        val outcome = runBlocking {
            selector.select(
                candidates = pool,
                probe = SymbolProbe { sym ->
                    seen.add(sym.fqName)
                    // Always insufficient — never passes.
                    evidence(grepHits = 0, refFiles = listOf("A.kt"))
                },
            )
        }

        assertEquals(3, seen.size)
        outcome as SelectionOutcome.NoQualifyingSymbol
        assertEquals(3, outcome.candidatesInspected)
    }

    @Test
    fun `propagates probe failures so the CLI can map them to exit code 2`() {
        val sym = symbol("io.acme.demo.execute")
        val selector = SymbolSelector()

        val ex = assertThrows<IllegalStateException> {
            runBlocking {
                selector.select(
                    candidates = listOf(sym),
                    probe = SymbolProbe { error("daemon unavailable") },
                )
            }
        }
        assertNotNull(ex.message)
    }

    private fun staticProbe(vararg pairs: Pair<Symbol, SymbolEvidence>): SymbolProbe {
        val byFqn = pairs.associate { it.first.fqName to it.second }
        return SymbolProbe { sym ->
            byFqn[sym.fqName] ?: error("no canned evidence for ${sym.fqName}")
        }
    }

    private fun evidence(grepHits: Int, refFiles: List<String>): SymbolEvidence = SymbolEvidence(
        textSearch = DemoTextSearchSummary(
            totalMatches = grepHits,
            likelyCorrect = grepHits,
            ambiguous = 0,
            falsePositives = 0,
            filesTouched = refFiles.toSet().size,
            categoryCounts = mapOf<DemoTextMatchCategory, Int>(DemoTextMatchCategory.LIKELY_CORRECT to grepHits),
            sampleMatches = emptyList(),
        ),
        references = ReferencesResult(
            references = refFiles.map { path -> stubLocation(path) },
        ),
    )

    private fun stubLocation(filePath: String): Location = Location(
        filePath = filePath,
        startOffset = 0,
        endOffset = 1,
        startLine = 1,
        startColumn = 1,
        preview = "stub",
    )

    private fun symbol(
        fqName: String,
        visibility: SymbolVisibility = SymbolVisibility.PUBLIC,
    ): Symbol = Symbol(
        fqName = fqName,
        kind = SymbolKind.FUNCTION,
        location = stubLocation("/tmp/${fqName.substringAfterLast('.')}.kt"),
        visibility = visibility,
    )
}
