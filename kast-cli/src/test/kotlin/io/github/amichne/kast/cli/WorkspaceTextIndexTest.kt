package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SymbolVisibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorkspaceTextIndexTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `counts included Kotlin symbol occurrences and skips ignored directories`() {
        writeKotlinFile(
            "src/main/kotlin/io/github/amichne/kast/demo/Included.kt",
            "package io.github.amichne.kast.demo\nclass IncludedSymbol\n",
        )
        writeKotlinFile(
            "build/generated/kotlin/io/github/amichne/kast/demo/Ignored.kt",
            "package io.github.amichne.kast.demo\nclass IncludedSymbol\n",
        )

        val summary = WorkspaceTextIndex(tempDir)
            .analyze(demoSymbol("io.github.amichne.kast.demo.IncludedSymbol"))

        assertEquals(1, summary.totalMatches)
        assertEquals(1, summary.likelyCorrect)
        assertEquals(0, summary.falsePositives)
        assertEquals(1, summary.filesTouched)
    }

    @Test
    fun `classifies symbol names inside string literals as false positives`() {
        writeKotlinFile(
            "src/main/kotlin/io/github/amichne/kast/demo/StringLiteral.kt",
            """package io.github.amichne.kast.demo
               |fun label() = "IncludedSymbol"
            """.trimMargin(),
        )

        val summary = WorkspaceTextIndex(tempDir)
            .analyze(demoSymbol("io.github.amichne.kast.demo.IncludedSymbol"))

        assertEquals(1, summary.totalMatches)
        assertEquals(0, summary.likelyCorrect)
        assertEquals(1, summary.falsePositives)
        assertEquals(1, summary.categoryCounts.getValue(DemoTextMatchCategory.STRING))
    }

    @Test
    fun `classifies symbol names embedded in larger identifiers as substring false positives`() {
        writeKotlinFile(
            "src/main/kotlin/io/github/amichne/kast/demo/Substring.kt",
            "package io.github.amichne.kast.demo\nclass IncludedSymbolFactory\n",
        )

        val summary = WorkspaceTextIndex(tempDir)
            .analyze(demoSymbol("io.github.amichne.kast.demo.IncludedSymbol"))

        assertEquals(1, summary.totalMatches)
        assertEquals(0, summary.likelyCorrect)
        assertEquals(1, summary.falsePositives)
        assertEquals(1, summary.categoryCounts.getValue(DemoTextMatchCategory.SUBSTRING))
    }

    @Test
    fun `caps sample matches using WorkspaceTextIndex owned limit`() {
        repeat(WorkspaceTextIndex.SAMPLE_MATCH_LIMIT + 2) { index ->
            writeKotlinFile(
                "src/main/kotlin/io/github/amichne/kast/demo/Sampled$index.kt",
                "package io.github.amichne.kast.demo\nclass SampledSymbol$index { fun use(value: SampledSymbol) = value }\n",
            )
        }

        val summary = WorkspaceTextIndex(tempDir)
            .analyze(demoSymbol("io.github.amichne.kast.demo.SampledSymbol"))

        assertEquals(WorkspaceTextIndex.SAMPLE_MATCH_LIMIT + 2, summary.totalMatches)
        assertEquals(WorkspaceTextIndex.SAMPLE_MATCH_LIMIT, summary.sampleMatches.size)
    }

    private fun writeKotlinFile(relativePath: String, content: String) {
        val file = tempDir.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun demoSymbol(fqName: String): Symbol {
        val simpleName = fqName.substringAfterLast('.')
        val preview = "class $simpleName"
        return Symbol(
            fqName = fqName,
            kind = SymbolKind.CLASS,
            location = Location(
                filePath = tempDir.resolve("src/main/kotlin/io/github/amichne/kast/demo/$simpleName.kt").toString(),
                startOffset = 0,
                endOffset = preview.length,
                startLine = 1,
                startColumn = 1,
                preview = preview,
            ),
            visibility = SymbolVisibility.PUBLIC,
            containingDeclaration = fqName.substringBeforeLast('.', ""),
        )
    }
}
