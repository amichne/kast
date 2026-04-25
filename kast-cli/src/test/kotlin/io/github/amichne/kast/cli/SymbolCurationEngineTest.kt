package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SymbolVisibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SymbolCurationEngineTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `returns first N groups in visit order (group-size desc then alphabetical) when all pass the bar`() {
        // Three ambiguous groups of equal size (2); all pass threshold=0.0 for this tiny codebase.
        // Visit order is alphabetical within same group size: High, Low, Mid.
        // count=2 → accepts the first two visited: High and Low (not High and Mid).
        val highNoise = makeSymbol("a.pkg.High")
        val highNoise2 = makeSymbol("b.pkg.High")
        val lowNoise = makeSymbol("a.pkg.Low")
        val lowNoise2 = makeSymbol("b.pkg.Low")
        val midNoise = makeSymbol("a.pkg.Mid")
        val midNoise2 = makeSymbol("b.pkg.Mid")

        val search = RecordingTextSearchAnalyzer { symbol ->
            when (symbol.fqName.substringAfterLast('.')) {
                "High" -> summary(total = 50, falsePositives = 30, ambiguous = 10)
                "Mid" -> summary(total = 20, falsePositives = 5, ambiguous = 3)
                "Low" -> summary(total = 5, falsePositives = 0, ambiguous = 0)
                else -> summary(0, 0, 0)
            }
        }
        val engine = SymbolCurationEngine(search)

        val result = engine.curate(
            workspaceRoot = tempDir,
            allSymbols = listOf(highNoise, highNoise2, lowNoise, lowNoise2, midNoise, midNoise2),
            count = 2,
        )

        assertEquals(2, result.size)
        assertEquals("High", result[0].simpleName)
        assertEquals("Low", result[1].simpleName)
        assertEquals(listOf("High", "Low"), search.analyzedSimpleNames)
    }

    @Test
    fun `filters out single-instance groups when enough ambiguous candidates exist`() {
        val ambiguousA = makeSymbol("a.pkg.Foo")
        val ambiguousB = makeSymbol("b.pkg.Foo")
        val ambiguousC = makeSymbol("a.pkg.Baz")
        val ambiguousD = makeSymbol("b.pkg.Baz")
        val singleton = makeSymbol("a.pkg.Bar")

        val support = StubTextSearchAnalyzer { summary(total = 10, falsePositives = 5, ambiguous = 2) }
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

        val support = StubTextSearchAnalyzer { symbol ->
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
        val support = StubTextSearchAnalyzer { summary(total = 10, falsePositives = 3, ambiguous = 2) }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(tempDir, symbols, count = 5)

        val names = result.map { it.simpleName }
        assertEquals(names.toSet().size, names.size, "expected unique simple names, got $names")
        assertEquals(setOf("Foo", "Bar"), names.toSet())
    }

    @Test
    fun `empty input returns empty list`() {
        val support = StubTextSearchAnalyzer { summary(0, 0, 0) }
        val engine = SymbolCurationEngine(support)

        assertEquals(emptyList<CuratedSymbol>(), engine.curate(tempDir, emptyList(), count = 3))
    }

    @Test
    fun `count of zero returns empty list`() {
        val support = StubTextSearchAnalyzer { summary(total = 10, falsePositives = 5, ambiguous = 2) }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(
            workspaceRoot = tempDir,
            allSymbols = listOf(makeSymbol("a.pkg.Foo"), makeSymbol("b.pkg.Foo")),
            count = 0,
        )

        assertEquals(emptyList<CuratedSymbol>(), result)
    }

    @Test
    fun `large codebase threshold filters groups below the bar`() {
        // 500 symbols → threshold = 5.0.
        // Group "Strong": total=50, FP=30, ambiguous=10, size=2
        //   contrastScore = 50 * (30+10+1)/50 * 2 = 50 * 0.82 * 2 ≈ 82 → passes
        // Group "Weak": total=4, FP=0, ambiguous=0, size=2
        //   contrastScore = 4 * (0+0+1)/4 * 2 = 2.0 → fails threshold 5.0
        val symbols = buildList {
            repeat(500) { i -> add(makeSymbol("pkg$i.Noise$i")) }
        } + listOf(
            makeSymbol("a.pkg.Strong"),
            makeSymbol("b.pkg.Strong"),
            makeSymbol("a.pkg.Weak"),
            makeSymbol("b.pkg.Weak"),
        )

        val support = StubTextSearchAnalyzer { symbol ->
            when (symbol.fqName.substringAfterLast('.')) {
                "Strong" -> summary(total = 50, falsePositives = 30, ambiguous = 10)
                "Weak"   -> summary(total = 4, falsePositives = 0, ambiguous = 0)
                else     -> summary(total = 1, falsePositives = 0, ambiguous = 0)
            }
        }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(workspaceRoot = tempDir, allSymbols = symbols, count = 3)

        assertTrue(result.any { it.simpleName == "Strong" }, "Strong should pass: $result")
        assertFalse(result.any { it.simpleName == "Weak" }, "Weak should be filtered: $result")
    }

    @Test
    fun `returns fewer than N when not enough groups pass the bar`() {
        // 300 symbols → threshold = 5.0.
        // Only one group passes; count=3 → returns 1 (not 3, not an error).
        val noiseSymbols = buildList {
            repeat(300) { i -> add(makeSymbol("pkg$i.Noise$i")) }
        }
        val passingGroup = listOf(makeSymbol("a.pkg.Rare"), makeSymbol("b.pkg.Rare"))

        val support = StubTextSearchAnalyzer { symbol ->
            if (symbol.fqName.substringAfterLast('.') == "Rare") {
                summary(total = 50, falsePositives = 30, ambiguous = 10) // score ≈ 82 → passes
            } else {
                summary(total = 1, falsePositives = 0, ambiguous = 0) // score ≈ 2 → fails 5.0 bar
            }
        }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(workspaceRoot = tempDir, allSymbols = noiseSymbols + passingGroup, count = 3)

        assertEquals(1, result.size, "Should return only the 1 passing group, not fail: $result")
        assertEquals("Rare", result[0].simpleName)
    }

    @Test
    fun `minContrastThreshold scales with codebase size`() {
        assertEquals(0.0, minContrastThreshold(0))
        assertEquals(0.0, minContrastThreshold(49))
        assertEquals(1.0, minContrastThreshold(50))
        assertEquals(1.0, minContrastThreshold(199))
        assertEquals(5.0, minContrastThreshold(200))
        assertEquals(5.0, minContrastThreshold(999))
        assertEquals(15.0, minContrastThreshold(1000))
        assertEquals(15.0, minContrastThreshold(4999))
        assertEquals(30.0, minContrastThreshold(5000))
    }

    @Test
    fun `larger ambiguous groups are visited before smaller ones`() {
        // Foo has 3 overloads, Bar has 2 overloads — Foo should appear first.
        val symbols = listOf(
            makeSymbol("a.pkg.Foo"), makeSymbol("b.pkg.Foo"), makeSymbol("c.pkg.Foo"),
            makeSymbol("a.pkg.Bar"), makeSymbol("b.pkg.Bar"),
        )
        val support = StubTextSearchAnalyzer { summary(total = 10, falsePositives = 3, ambiguous = 2) }
        val engine = SymbolCurationEngine(support)

        val result = engine.curate(tempDir, symbols, count = 1)

        assertEquals(1, result.size)
        assertEquals("Foo", result[0].simpleName)
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

    private class RecordingTextSearchAnalyzer(
        private val summarize: (Symbol) -> DemoTextSearchSummary,
    ) : TextSearchAnalyzer {
        val analyzedSimpleNames = mutableListOf<String>()

        override fun analyze(
            workspaceRoot: Path,
            symbol: Symbol,
        ): DemoTextSearchSummary {
            analyzedSimpleNames += symbol.fqName.substringAfterLast('.')
            return summarize(symbol)
        }
    }

    private class StubTextSearchAnalyzer(
        private val summarize: (Symbol) -> DemoTextSearchSummary,
    ) : TextSearchAnalyzer {
        override fun analyze(
            workspaceRoot: Path,
            symbol: Symbol,
        ): DemoTextSearchSummary = summarize(symbol)
    }
}
