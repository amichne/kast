package io.github.amichne.kast.cli

import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CallNode
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.Symbol
import java.io.Console
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.math.max

internal class DemoCommandSupport(
    private val symbolChooser: DemoSymbolChooser = TerminalDemoSymbolChooser(),
    private val themeProvider: () -> CliTextTheme = CliTextTheme::detect,
) {
    fun selectSymbol(
        options: DemoOptions,
        symbols: List<Symbol>,
    ): Symbol {
        if (symbols.isEmpty()) {
            throw CliFailure(
                code = "DEMO_NO_SYMBOLS",
                message = "Could not find any workspace symbols for `kast demo` in ${options.workspaceRoot}",
            )
        }
        val filter = options.symbolFilter?.takeIf(String::isNotBlank)
        return when {
            filter == null && symbols.size == 1 -> symbols.single()
            filter == null -> symbolChooser.choose(symbols)
            else -> symbols.firstOrNull { symbolMatchesFilter(it, filter) } ?: symbols.first()
        }
    }

    fun analyzeTextSearch(
        workspaceRoot: Path,
        symbol: Symbol,
    ): DemoTextSearchSummary {
        val symbolName = symbol.fqName.substringAfterLast('.')
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

        Files.walk(workspaceRoot).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .filter { path -> !isIgnoredSearchPath(path) }
                .forEach { filePath ->
                    filePath.readLines().forEachIndexed { index, line ->
                        if (!line.contains(symbolName)) {
                            return@forEachIndexed
                        }
                        val category = classifyTextMatch(line, symbolName)
                        touchedFiles += filePath.toString()
                        when (category) {
                            DemoTextMatchCategory.LIKELY_CORRECT -> likelyCorrect += 1
                            DemoTextMatchCategory.IMPORT -> {
                                ambiguous += 1
                                categoryCounts[DemoTextMatchCategory.IMPORT] = categoryCounts.getValue(DemoTextMatchCategory.IMPORT) + 1
                            }

                            DemoTextMatchCategory.COMMENT,
                            DemoTextMatchCategory.STRING,
                            DemoTextMatchCategory.SUBSTRING,
                            -> {
                                falsePositives += 1
                                categoryCounts[category] = categoryCounts.getValue(category) + 1
                            }
                        }
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

    fun render(report: DemoReport): String {
        val theme = themeProvider()
        val symbolName = report.selectedSymbol.fqName.substringAfterLast('.')
        return buildString {
            appendLine(theme.title("kast demo"))
            appendLine("Workspace  ${report.workspaceRoot}")
            appendLine("Selected   ${report.resolvedSymbol.fqName}")
            appendLine()

            appendLine(theme.heading("Act 1 · text search baseline"))
            appendLine("grep found ${report.textSearch.totalMatches} matches for \"$symbolName\"")
            appendLine("- likely true: ${report.textSearch.likelyCorrect}")
            appendLine("- ambiguous: ${report.textSearch.ambiguous}")
            appendLine("- likely false positives: ${report.textSearch.falsePositives}")
            appendLine("- blind rename would touch ${report.textSearch.filesTouched} files")
            report.textSearch.sampleMatches.forEach { match ->
                appendLine(
                    "  ${relativeToWorkspace(report.workspaceRoot, match.filePath)}:${match.lineNumber}  " +
                        "${match.preview} ${match.category.renderHint(theme)}",
                )
            }
            appendLine()

            appendLine(theme.heading("Act 2 · semantic analysis"))
            appendLine("resolve")
            appendLine("- fqName: ${report.resolvedSymbol.fqName}")
            appendLine("- kind: ${report.resolvedSymbol.kind}")
            report.resolvedSymbol.visibility?.let { appendLine("- visibility: $it") }
            appendLine("- location: ${renderLocation(report.workspaceRoot, report.resolvedSymbol.location)}")
            report.resolvedSymbol.containingDeclaration?.let { appendLine("- container: $it") }
            appendLine()

            appendLine("references")
            appendLine("- semantic references: ${report.references.references.size}")
            report.references.searchScope?.let { scope ->
                appendLine("- exhaustive: ${scope.exhaustive}")
                appendLine("- searched: ${scope.searchedFileCount}/${scope.candidateFileCount} files (${scope.scope})")
            }
            report.references.references.take(REFERENCE_PREVIEW_LIMIT).forEach { reference ->
                appendLine("  ${renderLocation(report.workspaceRoot, reference)}  ${reference.preview.trim()}")
            }
            appendLine()

            appendLine("rename --dry-run")
            appendLine("- edits: ${report.rename.edits.size}")
            appendLine("- affected files: ${report.rename.affectedFiles.size}")
            appendLine("- file hashes: ${report.rename.fileHashes.size}")
            report.rename.affectedFiles.take(FILE_PREVIEW_LIMIT).forEach { filePath ->
                appendLine("  ${relativeToWorkspace(report.workspaceRoot, filePath)}")
            }
            appendLine()

            appendLine("call-hierarchy (incoming, depth=2)")
            appendLine("- incoming callers: ${report.callHierarchy.stats.totalNodes}")
            appendLine("- max depth: ${report.callHierarchy.stats.maxDepthReached}")
            appendLine("- files visited: ${report.callHierarchy.stats.filesVisited}")
            renderCallTree(report.workspaceRoot, report.callHierarchy.root).forEach(::appendLine)
            appendLine()

            appendLine(theme.heading("Side-by-side summary"))
            append(renderComparisonTable(report, theme))
            appendLine()
            appendLine()

            appendLine(theme.heading("why the semantic pass wins"))
            appendLine("grep only sees text, so it mixes real usages with imports, comments, strings, and substring collisions.")
            appendLine("kast resolves the exact declaration, returns true semantic references, previews a safe rename, and maps incoming callers before you edit anything.")
        }
    }

    private fun renderCallTree(
        workspaceRoot: Path,
        root: CallNode,
    ): List<String> {
        val lines = mutableListOf<String>()
        val remaining = ArrayDeque<Int>().apply { add(CALL_TREE_LIMIT) }

        fun walk(node: CallNode, depth: Int) {
            val left = remaining.removeFirst()
            if (left <= 0) {
                remaining.addFirst(0)
                return
            }
            remaining.addFirst(left - 1)
            val symbol = node.symbol
            val indent = "  ".repeat(max(0, depth))
            lines += buildString {
                append(indent)
                append("- ")
                append(symbol.fqName.substringAfterLast('.'))
                append(" (")
                append(symbol.kind)
                append(") ")
                append(renderLocation(workspaceRoot, symbol.location))
            }
            node.children.forEach { child -> walk(child, depth + 1) }
        }

        walk(root, depth = 0)
        return lines
    }

    private fun renderComparisonTable(
        report: DemoReport,
        theme: CliTextTheme,
    ): String {
        val rows = listOf(
            Triple(
                "Matches found",
                "${report.textSearch.totalMatches} total / ${report.textSearch.likelyCorrect} likely true / ${report.textSearch.ambiguous} ambiguous",
                "${report.references.references.size} semantic references",
            ),
            Triple("Symbol identity", "text only", "exact symbol identity"),
            Triple("Kind awareness", "none", "knows the declaration kind"),
            Triple("Call graph", "none", "${report.callHierarchy.stats.totalNodes} incoming callers"),
            Triple("Rename plan", "blind sed across ${report.textSearch.filesTouched} files", "${report.rename.edits.size} edits across ${report.rename.affectedFiles.size} files"),
            Triple("Conflict detection", "none", "${report.rename.fileHashes.size} file hashes"),
            Triple(
                "Coverage signal",
                "none",
                report.references.searchScope?.let { "exhaustive=${it.exhaustive} over ${it.searchedFileCount}/${it.candidateFileCount} files" } ?: "scope unavailable",
            ),
            Triple("Post-edit checks", "manual", "kast diagnostics"),
        )
        val metricWidth = max("metric".length, rows.maxOf { it.first.length })
        val grepWidth = max("grep + sed".length, rows.maxOf { it.second.length })
        val kastWidth = max("kast".length, rows.maxOf { it.third.length })
        val separator = "${"-".repeat(metricWidth)}-+-${"-".repeat(grepWidth)}-+-${"-".repeat(kastWidth)}"
        return buildString {
            appendLine("metric".padEnd(metricWidth) + " | " + "grep + sed".padEnd(grepWidth) + " | " + theme.command("kast").padEnd(kastWidth))
            appendLine(separator)
            rows.forEachIndexed { index, row ->
                append(row.first.padEnd(metricWidth))
                append(" | ")
                append(row.second.padEnd(grepWidth))
                append(" | ")
                append(row.third.padEnd(kastWidth))
                if (index < rows.lastIndex) {
                    appendLine()
                }
            }
        }
    }

    private fun relativeToWorkspace(
        workspaceRoot: Path,
        filePath: String,
    ): String {
        val absolutePath = Path.of(filePath).toAbsolutePath().normalize()
        return absolutePath
            .takeIf { it.startsWith(workspaceRoot.toAbsolutePath().normalize()) }
            ?.let { workspaceRoot.toAbsolutePath().normalize().relativize(it).toString() }
            ?: absolutePath.toString()
    }

    private fun renderLocation(
        workspaceRoot: Path,
        location: Location,
    ): String = "${relativeToWorkspace(workspaceRoot, location.filePath)}:${location.startLine}"

    private fun symbolMatchesFilter(
        symbol: Symbol,
        filter: String,
    ): Boolean {
        val simpleName = symbol.fqName.substringAfterLast('.')
        return symbol.fqName == filter || simpleName == filter || symbol.fqName.endsWith(".$filter")
    }

    private fun classifyTextMatch(
        line: String,
        symbolName: String,
    ): DemoTextMatchCategory {
        val trimmed = line.trimStart()
        return when {
            trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") -> DemoTextMatchCategory.COMMENT
            trimmed.startsWith("import ") -> DemoTextMatchCategory.IMPORT
            appearsInsideStringLiteral(line, symbolName) -> DemoTextMatchCategory.STRING
            appearsAsSubstring(line, symbolName) -> DemoTextMatchCategory.SUBSTRING
            else -> DemoTextMatchCategory.LIKELY_CORRECT
        }
    }

    private fun appearsInsideStringLiteral(
        line: String,
        symbolName: String,
    ): Boolean = Regex("""["'][^"']*${Regex.escape(symbolName)}[^"']*["']""").containsMatchIn(line)

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

    private fun isIgnoredSearchPath(path: Path): Boolean = path.any { segment ->
        val segmentName = segment.name
        segmentName.startsWith(".") || segmentName in IGNORED_DIRECTORIES
    }

    private fun DemoTextMatchCategory.renderHint(theme: CliTextTheme): String = when (this) {
        DemoTextMatchCategory.LIKELY_CORRECT -> ""
        DemoTextMatchCategory.IMPORT -> theme.muted("← import")
        DemoTextMatchCategory.COMMENT -> theme.muted("← comment")
        DemoTextMatchCategory.STRING -> theme.muted("← string")
        DemoTextMatchCategory.SUBSTRING -> theme.muted("← substring")
    }

    private companion object {
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
        const val REFERENCE_PREVIEW_LIMIT = 8
        const val FILE_PREVIEW_LIMIT = 6
        const val CALL_TREE_LIMIT = 12
    }
}

internal fun interface DemoSymbolChooser {
    fun choose(candidates: List<Symbol>): Symbol
}

internal class TerminalDemoSymbolChooser(
    private val consoleProvider: () -> Console? = System::console,
    private val promptSink: (String) -> Unit = System.err::println,
) : DemoSymbolChooser {
    override fun choose(candidates: List<Symbol>): Symbol {
        val preview = candidates.take(PROMPT_LIMIT)
        val console = consoleProvider() ?: return preview.first()
        promptSink("Select a symbol for `kast demo`:")
        preview.forEachIndexed { index, symbol ->
            promptSink("${index + 1}. ${symbol.kind.name.lowercase()} ${symbol.fqName}")
        }
        if (candidates.size > preview.size) {
            promptSink("Showing the first ${preview.size} of ${candidates.size} symbols. Press Enter for 1.")
        }
        val selectedIndex = console.readLine("Symbol [1]: ")
            ?.trim()
            ?.toIntOrNull()
            ?.minus(1)
        return preview.getOrNull(selectedIndex ?: 0) ?: preview.first()
    }

    private companion object {
        const val PROMPT_LIMIT = 12
    }
}

internal enum class DemoTextMatchCategory {
    LIKELY_CORRECT,
    COMMENT,
    STRING,
    IMPORT,
    SUBSTRING,
}

internal data class DemoTextMatch(
    val filePath: String,
    val lineNumber: Int,
    val preview: String,
    val category: DemoTextMatchCategory,
)

internal data class DemoTextSearchSummary(
    val totalMatches: Int,
    val likelyCorrect: Int,
    val ambiguous: Int,
    val falsePositives: Int,
    val filesTouched: Int,
    val categoryCounts: Map<DemoTextMatchCategory, Int>,
    val sampleMatches: List<DemoTextMatch>,
)

internal data class DemoReport(
    val workspaceRoot: Path,
    val selectedSymbol: Symbol,
    val textSearch: DemoTextSearchSummary,
    val resolvedSymbol: Symbol,
    val references: ReferencesResult,
    val rename: RenameResult,
    val callHierarchy: CallHierarchyResult,
)
