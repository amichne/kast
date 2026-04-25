package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.Symbol
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines

internal class WorkspaceTextIndex(
    private val workspaceRoot: Path,
    private val ignoredDirectories: Set<String> = IGNORED_DIRECTORIES,
) {
    private val fileContents: Map<Path, List<String>> by lazy { buildIndex() }

    fun analyze(symbol: Symbol): DemoTextSearchSummary {
        val symbolName = symbol.fqName.substringAfterLast('.')
        val stringLiteralRegex = Regex("""["'][^"']*${Regex.escape(symbolName)}[^"']*["']""")
        val categoryCounts = mutableMapOf(
            DemoTextMatchCategory.COMMENT to 0,
            DemoTextMatchCategory.STRING to 0,
            DemoTextMatchCategory.IMPORT to 0,
            DemoTextMatchCategory.SUBSTRING to 0,
        )
        val sampleMatches = mutableListOf<DemoTextMatch>()
        val touchedFiles = linkedSetOf<String>()
        var likelyCorrect = 0
        var ambiguous = 0
        var falsePositives = 0

        fileContents.forEach { (filePath, lines) ->
            lines.forEachIndexed { index, line ->
                if (!line.contains(symbolName)) {
                    return@forEachIndexed
                }
                val category = classifyTextMatch(line, symbolName, stringLiteralRegex)
                when (category) {
                    DemoTextMatchCategory.IMPORT -> {
                        ambiguous += 1
                        categoryCounts[DemoTextMatchCategory.IMPORT] = categoryCounts.getValue(DemoTextMatchCategory.IMPORT) + 1
                    }

                    DemoTextMatchCategory.COMMENT,
                    DemoTextMatchCategory.SUBSTRING,
                    DemoTextMatchCategory.STRING -> {
                        falsePositives += 1
                        categoryCounts[category] = categoryCounts.getValue(category) + 1
                    }

                    else -> likelyCorrect += 1
                }
                touchedFiles += filePath.toString()
                if (sampleMatches.size < SAMPLE_MATCH_LIMIT) {
                    sampleMatches += DemoTextMatch(
                        filePath = filePath.toString(),
                        lineNumber = index + 1,
                        preview = line.trim(),
                        category = category,
                    )
                }
            }
        }

        return DemoTextSearchSummary(
            totalMatches = likelyCorrect + ambiguous + falsePositives,
            likelyCorrect = likelyCorrect,
            ambiguous = ambiguous,
            falsePositives = falsePositives,
            filesTouched = touchedFiles.size,
            categoryCounts = categoryCounts,
            sampleMatches = sampleMatches,
        )
    }

    private fun classifyTextMatch(
        line: String,
        symbolName: String,
        stringLiteralRegex: Regex,
    ): DemoTextMatchCategory = when {
        line.trimStart().startsWith("//") || line.trimStart().startsWith("/*") || line.trimStart().startsWith("*") -> DemoTextMatchCategory.COMMENT
        line.trimStart().startsWith("import ") -> DemoTextMatchCategory.IMPORT
        stringLiteralRegex.containsMatchIn(line) -> DemoTextMatchCategory.STRING
        appearsAsSubstring(line, symbolName) -> DemoTextMatchCategory.SUBSTRING
        else -> DemoTextMatchCategory.LIKELY_CORRECT
    }

    private fun appearsAsSubstring(
        line: String,
        symbolName: String,
    ): Boolean {
        var index = line.indexOf(symbolName)
        while (index >= 0) {
            val before = line.getOrNull(index - 1)
            val after = line.getOrNull(index + symbolName.length)
            if (before.isIdentifierBoundaryParticipant() || after.isIdentifierBoundaryParticipant()) {
                return true
            }
            index = line.indexOf(symbolName, startIndex = index + 1)
        }
        return false
    }

    private fun Char?.isIdentifierBoundaryParticipant(): Boolean = this?.let { it == '_' || it.isLetterOrDigit() } == true

    private fun buildIndex(): Map<Path, List<String>> = Files.walk(workspaceRoot).use { paths ->
        paths
            .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
            .filter { path -> !isIgnoredSearchPath(path) }
            .toList()
            .associateWith { path -> path.readLines() }
    }

    private fun isIgnoredSearchPath(path: Path): Boolean = path.any { segment ->
        val segmentName = segment.name
        segmentName.startsWith(".") || segmentName in ignoredDirectories
    }

    companion object {
        val IGNORED_DIRECTORIES = setOf(
            ".git",
            ".gradle",
            ".kast",
            "build",
            "out",
            "node_modules",
            ".idea",
            "build-logic",
            "buildSrc",
        )
        const val SAMPLE_MATCH_LIMIT = 12
    }
}
