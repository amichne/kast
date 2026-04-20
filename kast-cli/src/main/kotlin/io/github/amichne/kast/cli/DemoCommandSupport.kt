package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.CallHierarchyQuery
import io.github.amichne.kast.api.contract.CallHierarchyResult
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.RenameQuery
import io.github.amichne.kast.api.contract.RenameResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolQuery
import io.github.amichne.kast.api.contract.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.demo.CliServiceSymbolGraph
import io.github.amichne.kast.cli.demo.DemoActs.act1TextSearchBaseline
import io.github.amichne.kast.cli.demo.DemoActs.act2Semantic
import io.github.amichne.kast.cli.demo.DemoActs.closingPanel
import io.github.amichne.kast.cli.demo.DemoActs.comparisonSummary
import io.github.amichne.kast.cli.demo.DemoActs.openingBanner
import io.github.amichne.kast.cli.demo.DemoActs.targetPanel
import io.github.amichne.kast.cli.demo.DemoRenderer
import io.github.amichne.kast.cli.demo.DemoScript
import io.github.amichne.kast.cli.demo.LineEmphasis
import io.github.amichne.kast.cli.demo.StreamWalkerIO
import io.github.amichne.kast.cli.demo.SymbolWalker
import io.github.amichne.kast.cli.demo.Timed
import io.github.amichne.kast.cli.demo.demoScript
import io.github.amichne.kast.cli.demo.timed
import java.io.BufferedReader
import java.io.Console
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines

/** Shared entry points used by [CliService.demo] and directly by unit tests. */
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

    /**
     * Batch rendering used when the caller wants a complete transcript in
     * one shot (older callers + unit tests). Builds the full scene tree,
     * then renders it in a single pass.
     */
    fun render(report: DemoReport): String {
        val renderer = newRenderer()
        val script = demoScript {
            openingBanner(report.workspaceRoot)
            targetPanel(report.workspaceRoot, report.resolvedSymbol)
            act1TextSearchBaseline(
                workspaceRoot = report.workspaceRoot,
                symbolName = report.resolvedSymbol.fqName.substringAfterLast('.'),
                summary = report.textSearch,
            )
            act2Semantic(
                workspaceRoot = report.workspaceRoot,
                resolvedSymbol = report.resolvedSymbol,
                references = report.references,
                rename = report.rename,
                callHierarchy = report.callHierarchy,
            )
            comparisonSummary(report)
            closingPanel()
        }
        return renderer.render(script)
    }

    /**
     * Streaming orchestrator. Gathers the analysis payload piece by piece,
     * emits each scene to [sink] as it completes, optionally runs the
     * interactive symbol-graph walker, and finally prints the comparison
     * summary and closing panel. Returns the populated [DemoReport] so the
     * caller can attach it to a runtime-aware result.
     */
    suspend fun runInteractive(
        options: DemoOptions,
        cliService: CliService,
        sink: (String) -> Unit,
        reader: BufferedReader?,
        walkerEnabled: Boolean,
    ): DemoReport {
        val renderer = newRenderer()
        fun emit(script: DemoScript) = sink(renderer.render(script))

        emit(demoScript { openingBanner(options.workspaceRoot) })

        val runtimeOptions = RuntimeCommandOptions(
            workspaceRoot = options.workspaceRoot,
            backendName = options.backend,
            waitTimeoutMillis = 180_000L,
        )

        emit(demoScript { progress("Warming workspace daemon (kast workspace ensure)...") })
        val warm = timed { cliService.workspaceEnsure(runtimeOptions) }
        emit(stepOutcomeScript("workspace ensure", warm))
        warm.value.getOrThrow()

        emit(demoScript { progress("Discovering workspace symbols (kast workspace-symbol)...") })
        val symbolSearch = timed {
            cliService.workspaceSymbolSearch(
                runtimeOptions,
                WorkspaceSymbolQuery(
                    pattern = options.symbolFilter ?: ".",
                    maxResults = 500,
                    regex = options.symbolFilter == null,
                ),
            )
        }
        emit(stepOutcomeScript("workspace symbol search", symbolSearch))
        val symbolPayload = symbolSearch.value.getOrThrow().payload

        val selectedSymbol = selectSymbol(options, symbolPayload.symbols)
        val symbolPosition = FilePosition(
            filePath = selectedSymbol.location.filePath,
            offset = selectedSymbol.location.startOffset,
        )

        emit(demoScript { targetPanel(options.workspaceRoot, selectedSymbol) })

        emit(demoScript { progress("Classifying grep matches for ${selectedSymbol.fqName.substringAfterLast('.')}...") })
        val textSearch = analyzeTextSearch(options.workspaceRoot, selectedSymbol)
        emit(demoScript {
            act1TextSearchBaseline(
                workspaceRoot = options.workspaceRoot,
                symbolName = selectedSymbol.fqName.substringAfterLast('.'),
                summary = textSearch,
            )
        })

        val resolved = timed {
            cliService.resolveSymbol(runtimeOptions, SymbolQuery(position = symbolPosition))
        }
        val resolvedSymbol = resolved.value.getOrThrow().payload.symbol

        val references = timed {
            cliService.findReferences(
                runtimeOptions,
                ReferencesQuery(position = symbolPosition, includeDeclaration = true),
            )
        }
        val referencesPayload = references.value.getOrThrow().payload

        val rename = timed {
            cliService.rename(
                runtimeOptions,
                RenameQuery(
                    position = symbolPosition,
                    newName = "${resolvedSymbol.fqName.substringAfterLast('.')}Renamed",
                    dryRun = true,
                ),
            )
        }
        val renamePayload = rename.value.getOrThrow().payload

        val callHierarchy = timed {
            cliService.callHierarchy(
                runtimeOptions,
                CallHierarchyQuery(
                    position = symbolPosition,
                    direction = CallDirection.INCOMING,
                    depth = 2,
                ),
            )
        }
        val callHierarchyPayload = callHierarchy.value.getOrThrow().payload

        emit(demoScript {
            act2Semantic(
                workspaceRoot = options.workspaceRoot,
                resolvedSymbol = resolvedSymbol,
                references = referencesPayload,
                rename = renamePayload,
                callHierarchy = callHierarchyPayload,
            )
        })

        if (walkerEnabled && reader != null) {
            val walker = SymbolWalker(
                workspaceRoot = options.workspaceRoot,
                graph = CliServiceSymbolGraph(cliService, runtimeOptions),
                io = StreamWalkerIO(reader = reader, output = sink),
                renderer = renderer,
            )
            walker.run(resolvedSymbol)
        } else {
            emit(demoScript {
                section("Act 3 · walk the symbol graph")
                panel("interactive walker · skipped") {
                    line(
                        text = "pass --walk=true with a real terminal to hop between references, callers, and callees.",
                        emphasis = LineEmphasis.DIM,
                    )
                }
                blank()
            })
        }

        val report = DemoReport(
            workspaceRoot = options.workspaceRoot,
            selectedSymbol = selectedSymbol,
            textSearch = textSearch,
            resolvedSymbol = resolvedSymbol,
            references = referencesPayload,
            rename = renamePayload,
            callHierarchy = callHierarchyPayload,
        )

        emit(demoScript {
            comparisonSummary(report)
            closingPanel()
        })

        return report
    }

    private fun newRenderer(): DemoRenderer = DemoRenderer(theme = themeProvider())

    private fun stepOutcomeScript(message: String, outcome: Timed<Result<*>>): DemoScript = demoScript {
        step(message) {
            if (outcome.value.isSuccess) success(outcome.elapsed) else failure(outcome.elapsed)
            if (outcome.value.isFailure) {
                body {
                    line(
                        text = outcome.value.exceptionOrNull()?.message ?: "unknown failure",
                        emphasis = LineEmphasis.ERROR,
                    )
                }
            }
        }
    }

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

/** Convenience reader for CLI plumbing. */
internal fun defaultDemoReader(): BufferedReader =
    BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
