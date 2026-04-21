package io.github.amichne.kast.cli

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
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
import io.github.amichne.kast.cli.demo.DemoTerminal
import io.github.amichne.kast.cli.demo.FzfWalkerIO
import io.github.amichne.kast.cli.demo.StreamWalkerIO
import io.github.amichne.kast.cli.demo.DemoSelectionConfig
import io.github.amichne.kast.cli.demo.SelectionOutcome
import io.github.amichne.kast.cli.demo.SymbolDisplay
import io.github.amichne.kast.cli.demo.SymbolEvidence
import io.github.amichne.kast.cli.demo.SymbolProbe
import io.github.amichne.kast.cli.demo.SymbolSelector
import io.github.amichne.kast.cli.demo.SymbolWalker
import io.github.amichne.kast.cli.demo.WalkerIO
import io.github.amichne.kast.cli.demo.Timed
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
    /**
     * Picks the symbol that the demo will narrate around. When the user
     * supplies `--symbol` we honour that filter as before. Otherwise the
     * heuristic [SymbolSelector] is run so the demo always lands on a
     * symbol where the grep-vs-semantic comparison is interesting.
     *
     * The probe used to score candidates also produces the evidence we
     * later render in Act 1, so we cache it on the returned
     * [DemoSubjectSelection] to avoid re-running text search and reference
     * lookup against the chosen symbol.
     */
    private suspend fun resolveDemoSubject(
        options: DemoOptions,
        cliService: CliService,
        runtimeOptions: RuntimeCommandOptions,
        candidates: List<Symbol>,
        emit: (String) -> Unit,
    ): DemoSubjectSelection {
        if (candidates.isEmpty()) {
            throw CliFailure(
                code = "DEMO_NO_SYMBOLS",
                message = "Could not find any workspace symbols for `kast demo` in ${options.workspaceRoot}",
            )
        }
        val filter = options.symbolFilter?.takeIf(String::isNotBlank)
        if (filter != null) {
            return DemoSubjectSelection(symbol = selectSymbol(options, candidates))
        }
        if (candidates.size == 1) {
            return DemoSubjectSelection(symbol = candidates.single())
        }

        emit("› Auto-selecting a noisy symbol (--min-refs=${options.minRefs}, --noise-ratio=${options.noiseRatio})...")
        val selector = SymbolSelector(
            DemoSelectionConfig(minRefs = options.minRefs, noiseRatio = options.noiseRatio),
        )
        val probe = SymbolProbe { candidate ->
            val textSearch = analyzeTextSearch(options.workspaceRoot, candidate)
            val candidatePosition = FilePosition(
                filePath = candidate.location.filePath,
                offset = candidate.location.startOffset,
            )
            val references = cliService.findReferences(
                runtimeOptions,
                ReferencesQuery(position = candidatePosition, includeDeclaration = true),
            ).payload
            SymbolEvidence(textSearch = textSearch, references = references)
        }
        return when (val outcome = selector.select(candidates, probe)) {
            is SelectionOutcome.Found -> DemoSubjectSelection(
                symbol = outcome.symbol,
                evidence = outcome.evidence,
            )
            is SelectionOutcome.NoQualifyingSymbol -> throw CliFailure(
                code = "DEMO_NO_QUALIFYING_SYMBOL",
                message = "No symbol satisfied the demo thresholds (${outcome.reason}). " +
                    "Pass --symbol to pick one explicitly, or relax --min-refs / --noise-ratio.",
            )
        }
    }

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
            else -> pickBestMatch(symbols, filter) ?: symbols.first()
        }
    }

    /**
     * Choose the [Symbol] that best matches a user filter. Prefers an exact
     * `fqName` match so callers can disambiguate overloaded simple names by
     * passing a fully-qualified class name; falls back through suffix and
     * substring matches in that order.
     */
    private fun pickBestMatch(symbols: List<Symbol>, filter: String): Symbol? {
        val exact = symbols.firstOrNull { it.fqName == filter }
        if (exact != null) return exact
        val suffix = symbols.firstOrNull { it.fqName.endsWith(".$filter") }
        if (suffix != null) return suffix
        val simple = symbols.firstOrNull { it.fqName.substringAfterLast('.') == filter }
        if (simple != null) return simple
        return symbols.firstOrNull { symbolMatchesFilter(it, filter) }
    }

    /**
     * Build the server-side query for a user-provided symbol filter. FQNs
     * are split so the daemon can find the declaration by simple name — the
     * client then re-filters by FQN exactness. Substring inputs flow through
     * as-is with `regex=false`.
     */
    internal fun workspaceSymbolQueryFor(filter: String?): WorkspaceSymbolQuery {
        val trimmed = filter?.takeIf(String::isNotBlank)
        return when {
            trimmed == null -> WorkspaceSymbolQuery(pattern = ".", maxResults = 500, regex = true)
            trimmed.contains('.') -> WorkspaceSymbolQuery(
                pattern = trimmed.substringAfterLast('.'),
                maxResults = 500,
                regex = false,
            )
            else -> WorkspaceSymbolQuery(pattern = trimmed, maxResults = 500, regex = false)
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
     * one shot (older callers + unit tests). Builds a captured
     * [DemoTerminal] and emits each act in order.
     */
    fun render(report: DemoReport): String {
        val recorder = TerminalRecorder(
            ansiLevel = AnsiLevel.NONE,
            width = 100,
            hyperlinks = false,
            outputInteractive = false,
            inputInteractive = false,
        )
        val ui = DemoTerminal(terminal = Terminal(terminalInterface = recorder))
        ui.emit(
            ui.act1TextSearchBaseline(
                workspaceRoot = report.workspaceRoot,
                symbolName = report.resolvedSymbol.fqName.substringAfterLast('.'),
                summary = report.textSearch,
            )
        )
        ui.blankLine()
        ui.emit(
            ui.act2Semantic(
                workspaceRoot = report.workspaceRoot,
                textSearch = report.textSearch,
                resolvedSymbol = report.resolvedSymbol,
                references = report.references,
                rippleEnabled = false,
            )
        )
        ui.blankLine()
        ui.emit(
            ui.act3CallerTree(
                workspaceRoot = report.workspaceRoot,
                callHierarchy = report.callHierarchy,
                depth = 2,
            )
        )
        return recorder.stdout()
    }

    /**
     * Streaming orchestrator. Gathers the analysis payload piece by piece,
     * emits the three demo acts to [sink], and optionally hands off to the
     * interactive symbol-graph walker once the transcript has landed.
     * Returns the populated [DemoReport] so the caller can attach it to a
     * runtime-aware result.
     */
    suspend fun runInteractive(
        options: DemoOptions,
        cliService: CliService,
        sink: (String) -> Unit,
        reader: BufferedReader?,
        walkerEnabled: Boolean,
    ): DemoReport {
        // When a reader is present we're on a real TTY — create a live
        // terminal so the Act 1 streaming animation can drive cursor updates.
        val liveTerminal: Terminal? = if (reader != null) Terminal() else null
        val ui = DemoTerminal.captured(sink = sink, animationTerminal = liveTerminal)
        fun emitOutcome(message: String, outcome: Timed<Result<*>>) {
            ui.emit(
                ui.stepOutcome(
                    message = message,
                    success = outcome.value.isSuccess,
                    elapsed = outcome.elapsed,
                )
            )
            if (outcome.value.isFailure) {
                ui.emit(ui.stepFailureBody(outcome.value.exceptionOrNull()?.message ?: "unknown failure"))
            }
        }

        val runtimeOptions = RuntimeCommandOptions(
            workspaceRoot = options.workspaceRoot,
            backendName = options.backend,
            waitTimeoutMillis = 180_000L,
        )

        val warm = timed { cliService.workspaceEnsure(runtimeOptions) }
        warm.value.getOrThrow()

        val searchQuery = workspaceSymbolQueryFor(options.symbolFilter)
        val symbolSearch = timed { cliService.workspaceSymbolSearch(runtimeOptions, searchQuery) }
        val symbolPayload = symbolSearch.value.getOrThrow().payload

        val resolvedSelection = resolveDemoSubject(
            options = options,
            cliService = cliService,
            runtimeOptions = runtimeOptions,
            candidates = symbolPayload.symbols,
            emit = sink,
        )
        val selectedSymbol = resolvedSelection.symbol
        val symbolPosition = FilePosition(
            filePath = selectedSymbol.location.filePath,
            offset = selectedSymbol.location.startOffset,
        )

        val textSearch = resolvedSelection.evidence?.textSearch
            ?: analyzeTextSearch(options.workspaceRoot, selectedSymbol)
        val symbolSimpleName = selectedSymbol.fqName.substringAfterLast('.')
        val animationRan = if (reader != null) {
            ui.act1StreamingAnimation(
                symbolName = symbolSimpleName,
                estimatedTotal = textSearch.totalMatches,
                onComplete = {},
            )
            true
        } else {
            false
        }
        ui.emit(
            ui.act1TextSearchBaseline(
                workspaceRoot = options.workspaceRoot,
                symbolName = symbolSimpleName,
                summary = textSearch,
                includeHeader = !animationRan,
            )
        )
        ui.blankLine()

        val resolved = timed {
            cliService.resolveSymbol(runtimeOptions, SymbolQuery(position = symbolPosition))
        }
        val resolvedSymbol = resolved.value.getOrThrow().payload.symbol

        val referencesPayload = resolvedSelection.evidence?.references ?: run {
            val references = timed {
                cliService.findReferences(
                    runtimeOptions,
                    ReferencesQuery(position = symbolPosition, includeDeclaration = true),
                )
            }
            references.value.getOrThrow().payload
        }

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
                    depth = options.rippleDepth,
                ),
            )
        }
        val callHierarchyPayload = callHierarchy.value.getOrThrow().payload

        val rippleEnabled = true
        ui.emit(
            ui.act2Semantic(
                workspaceRoot = options.workspaceRoot,
                textSearch = textSearch,
                resolvedSymbol = resolvedSymbol,
                references = referencesPayload,
                rippleEnabled = rippleEnabled,
            )
        )
        ui.blankLine()

        if (rippleEnabled) {
            // Wait for the user to press Enter before flipping into Act 3.
            // In captured/test runs there is no reader, so we proceed immediately.
            if (reader != null) {
                runCatching { reader.readLine() }
            }
            ui.emit(
                ui.act3CallerTree(
                    workspaceRoot = options.workspaceRoot,
                    callHierarchy = callHierarchyPayload,
                    depth = options.rippleDepth,
                )
            )
        }

        if (walkerEnabled && reader != null) {
            val base: WalkerIO = StreamWalkerIO(reader = reader, output = sink)
            val io: WalkerIO = FzfWalkerIO.locateFzf()
                ?.takeIf { System.console() != null }
                ?.let { FzfWalkerIO(delegate = base, fzfPath = it) }
                ?: base
            val walker = SymbolWalker(
                workspaceRoot = options.workspaceRoot,
                graph = CliServiceSymbolGraph(cliService, runtimeOptions),
                io = io,
                ui = ui,
                theme = themeProvider(),
                display = SymbolDisplay(
                    workspaceRoot = options.workspaceRoot,
                    verbose = options.verbose,
                ),
            )
            walker.run(resolvedSymbol)
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

        return report
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

/**
 * Result of picking the demo's subject symbol. When the heuristic
 * [SymbolSelector] runs the probe, it produces evidence (text search +
 * references) we then reuse instead of recomputing it for Act 1 / Act 2.
 */
internal data class DemoSubjectSelection(
    val symbol: Symbol,
    val evidence: SymbolEvidence? = null,
)

/** Convenience reader for CLI plumbing. */
internal fun defaultDemoReader(): BufferedReader =
    BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
