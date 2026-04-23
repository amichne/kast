package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SymbolVisibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SymbolCurationEngineTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `returns top N entries sorted by descending contrast score`() {
        // Two ambiguous groups (size 2) with different stub summaries → distinct contrast scores.
        val highNoise = makeSymbol("a.pkg.High")
        val highNoise2 = makeSymbol("b.pkg.High")
        val lowNoise = makeSymbol("a.pkg.Low")
        val lowNoise2 = makeSymbol("b.pkg.Low")
        val midNoise = makeSymbol("a.pkg.Mid")
        val midNoise2 = makeSymbol("b.pkg.Mid")

        val support = StubDemoCommandSupport { symbol ->
            when (symbol.fqName.substringAfterLast('.')) {
                "High" -> summary(total = 50, falsePositives = 30, ambiguous = 10)
                "Mid" -> summary(total = 20, falsePositives = 5, ambiguous = 3)
                "Low" -> summary(total = 5, falsePositives = 0, ambiguous = 0)
                else -> summary(0, 0, 0)
            }
        }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(
            workspaceRoot = tempDir,
            allSymbols = listOf(highNoise, highNoise2, lowNoise, lowNoise2, midNoise, midNoise2),
            count = 2,
        )

        assertEquals(2, result.size)
        assertEquals("High", result[0].simpleName)
        assertEquals("Mid", result[1].simpleName)
        assertTrue(result[0].contrastScore > result[1].contrastScore)
    }

    @Test
    fun `filters out single-instance groups when enough ambiguous candidates exist`() {
        val ambiguousA = makeSymbol("a.pkg.Foo")
        val ambiguousB = makeSymbol("b.pkg.Foo")
        val ambiguousC = makeSymbol("a.pkg.Baz")
        val ambiguousD = makeSymbol("b.pkg.Baz")
        val singleton = makeSymbol("a.pkg.Bar")

        val support = StubDemoCommandSupport { summary(total = 10, falsePositives = 5, ambiguous = 2) }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(
            workspaceRoot = tempDir,
            allSymbols = listOf(ambiguousA, ambiguousB, ambiguousC, ambiguousD, singleton),
            count = 2,
        )

        // With 2 ambiguous groups available and count=2, the singleton must not appear.
        assertEquals(2, result.size)
        assertTrue(result.none { it.simpleName == "Bar" }, "singleton should be filtered: $result")
        assertEquals(setOf("Foo", "Baz"), result.map { it.simpleName }.toSet())
    }

    @Test
    fun `falls back to single-instance groups when not enough ambiguous candidates`() {
        val ambiguousA = makeSymbol("a.pkg.Foo")
        val ambiguousB = makeSymbol("b.pkg.Foo")
        val singletonHi = makeSymbol("a.pkg.HighHits")
        val singletonLo = makeSymbol("a.pkg.LowHits")

        val support = StubDemoCommandSupport { symbol ->
            when (symbol.fqName.substringAfterLast('.')) {
                "Foo" -> summary(total = 4, falsePositives = 1, ambiguous = 1)
                "HighHits" -> summary(total = 100, falsePositives = 0, ambiguous = 0)
                "LowHits" -> summary(total = 2, falsePositives = 0, ambiguous = 0)
                else -> summary(0, 0, 0)
            }
        }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(
            workspaceRoot = tempDir,
            allSymbols = listOf(ambiguousA, ambiguousB, singletonHi, singletonLo),
            count = 3,
        )

        assertEquals(3, result.size)
        // Ambiguous group first, then singletons sorted by grepHits descending.
        assertEquals("Foo", result[0].simpleName)
        assertEquals("HighHits", result[1].simpleName)
        assertEquals("LowHits", result[2].simpleName)
    }

    @Test
    fun `picks one entry per simple name with no duplicates`() {
        val symbols = listOf(
            makeSymbol("a.pkg.Foo"),
            makeSymbol("b.pkg.Foo"),
            makeSymbol("c.pkg.Foo"),
            makeSymbol("a.pkg.Bar"),
            makeSymbol("b.pkg.Bar"),
        )
        val support = StubDemoCommandSupport { summary(total = 10, falsePositives = 3, ambiguous = 2) }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(tempDir, symbols, count = 5)

        val names = result.map { it.simpleName }
        assertEquals(names.toSet().size, names.size, "expected unique simple names, got $names")
        assertEquals(setOf("Foo", "Bar"), names.toSet())
    }

    @Test
    fun `empty input returns empty list`() {
        val support = StubDemoCommandSupport { summary(0, 0, 0) }
        val engine = SymbolCurationEngine(support)

        assertEquals(emptyList<CuratedSymbol>(), engine.curate(tempDir, emptyList(), count = 3))
    }

    @Test
    fun `count of zero returns empty list`() {
        val support = StubDemoCommandSupport { summary(total = 10, falsePositives = 5, ambiguous = 2) }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(
            workspaceRoot = tempDir,
            allSymbols = listOf(makeSymbol("a.pkg.Foo"), makeSymbol("b.pkg.Foo")),
            count = 0,
        )

        assertEquals(emptyList<CuratedSymbol>(), result)
    }

    private fun makeSymbol(fqName: String): Symbol {
        val simple = fqName.substringAfterLast('.')
        return Symbol(
            fqName = fqName,
            kind = SymbolKind.CLASS,
            location = Location(
                filePath = tempDir.resolve("$simple.kt").toString(),
                startOffset = 0,
                endOffset = simple.length,
                startLine = 1,
                startColumn = 1,
                preview = "class $simple",
            ),
            visibility = SymbolVisibility.PUBLIC,
            containingDeclaration = fqName.substringBeforeLast('.', ""),
        )
    }

    private fun summary(total: Int, falsePositives: Int, ambiguous: Int): DemoTextSearchSummary {
        val likely = (total - falsePositives - ambiguous).coerceAtLeast(0)
        return DemoTextSearchSummary(
            totalMatches = total,
            likelyCorrect = likely,
            ambiguous = ambiguous,
            falsePositives = falsePositives,
            filesTouched = 0,
            categoryCounts = emptyMap(),
            sampleMatches = emptyList(),
        )
    }

    private class StubDemoCommandSupport(
        private val summarize: (Symbol) -> DemoTextSearchSummary,
    ) : DemoCommandSupport() {
        override fun analyzeTextSearch(
            workspaceRoot: Path,
            symbol: Symbol,
        ): DemoTextSearchSummary = summarize(symbol)
    }
}
