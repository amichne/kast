package io.github.amichne.kast.cli

import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.runtime.Session
import com.varabyte.kotter.runtime.terminal.Terminal
import com.varabyte.kotter.terminal.system.SystemTerminal
import com.varabyte.kotter.terminal.virtual.VirtualTerminal
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.CallHierarchyQuery
import io.github.amichne.kast.api.contract.CallHierarchyResult
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.RenameQuery
import io.github.amichne.kast.api.contract.RenameResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolQuery
import io.github.amichne.kast.api.contract.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.demo.KotterDemoBranchSpec
import io.github.amichne.kast.cli.demo.KotterDemoOperationPresentation
import io.github.amichne.kast.cli.demo.KotterDemoOperationScenario
import io.github.amichne.kast.cli.demo.KotterDemoScenarioEvent
import io.github.amichne.kast.cli.demo.KotterDemoSessionPresentation
import io.github.amichne.kast.cli.demo.KotterDemoSessionScenario
import io.github.amichne.kast.cli.demo.KotterDemoStreamTone
import io.github.amichne.kast.cli.demo.KotterDemoTranscriptLine
import io.github.amichne.kast.cli.demo.Paths
import io.github.amichne.kast.cli.demo.SymbolPickerResult
import io.github.amichne.kast.cli.demo.renderCallTreePreview
import io.github.amichne.kast.cli.demo.runKotterDemoSession
import io.github.amichne.kast.cli.demo.runLoadingPhase
import io.github.amichne.kast.cli.demo.runSymbolPicker
import java.io.Console
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines

/** Shared entry points used by [CliService.demo] and directly by unit tests. */
internal class DemoCommandSupport(
    private val symbolChooser: DemoSymbolChooser = TerminalDemoSymbolChooser(),
    private val sessionRunner: KotterDemoSessionRunner = LiveKotterDemoSessionRunner(),
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

    suspend fun runInteractive(
        options: DemoOptions,
        cliService: CliService,
    ): DemoFlowOutcome {
        val runtimeOptions = RuntimeCommandOptions(
            workspaceRoot = options.workspaceRoot,
            backendName = options.backend,
            waitTimeoutMillis = 180_000L,
        )

        return sessionRunner.runSession(options.verbose) { terminal ->
            // Phase 1+2: Warm backend + symbol picker (combined in runSymbolPicker)
            var ensureResult: WorkspaceEnsureResult? = null
            val pickerResult = runSymbolPicker(
                verbose = options.verbose,
                searchSymbols = { query ->
                    cliService.workspaceSymbolSearch(runtimeOptions, query).payload
                },
                warmBackend = {
                    ensureResult = cliService.workspaceEnsure(runtimeOptions)
                },
            )

            val selectedSymbol = when (pickerResult) {
                is SymbolPickerResult.Cancelled -> return@runSession DemoFlowOutcome.Cancelled
                is SymbolPickerResult.Selected -> pickerResult.symbol
            }

            val symbolPosition = FilePosition(
                filePath = selectedSymbol.location.filePath,
                offset = selectedSymbol.location.startOffset,
            )

            // Phase 3: Load demo data with progress
            var resolvedSymbol: Symbol = selectedSymbol
            var referencesPayload: ReferencesResult? = null
            var renamePayload: RenameResult? = null
            var callHierarchyPayload: CallHierarchyResult? = null
            var textSearch: DemoTextSearchSummary? = null

            val loadSuccess = runLoadingPhase(
                symbolName = selectedSymbol.fqName.substringAfterLast('.'),
                steps = listOf("Resolve symbol", "Find references", "Rename dry-run", "Call hierarchy", "Text search"),
            ) { onStepComplete ->
                val t0 = System.currentTimeMillis()
                resolvedSymbol = cliService.resolveSymbol(
                    runtimeOptions,
                    SymbolQuery(position = symbolPosition),
                ).payload.symbol
                onStepComplete(0, System.currentTimeMillis() - t0)

                val t1 = System.currentTimeMillis()
                referencesPayload = cliService.findReferences(
                    runtimeOptions,
                    ReferencesQuery(position = symbolPosition, includeDeclaration = true),
                ).payload
                onStepComplete(1, System.currentTimeMillis() - t1)

                val t2 = System.currentTimeMillis()
                renamePayload = cliService.rename(
                    runtimeOptions,
                    RenameQuery(
                        position = symbolPosition,
                        newName = "${resolvedSymbol.fqName.substringAfterLast('.')}Renamed",
                        dryRun = true,
                    ),
                ).payload
                onStepComplete(2, System.currentTimeMillis() - t2)

                val t3 = System.currentTimeMillis()
                callHierarchyPayload = cliService.callHierarchy(
                    runtimeOptions,
                    CallHierarchyQuery(
                        position = symbolPosition,
                        direction = CallDirection.INCOMING,
                        depth = 2,
                    ),
                ).payload
                onStepComplete(3, System.currentTimeMillis() - t3)

                val t4 = System.currentTimeMillis()
                textSearch = analyzeTextSearch(options.workspaceRoot, selectedSymbol)
                onStepComplete(4, System.currentTimeMillis() - t4)
            }

            if (!loadSuccess) return@runSession DemoFlowOutcome.Failed("Backend queries failed")

            val report = DemoReport(
                workspaceRoot = options.workspaceRoot,
                selectedSymbol = selectedSymbol,
                textSearch = textSearch!!,
                resolvedSymbol = resolvedSymbol,
                references = referencesPayload!!,
                rename = renamePayload!!,
                callHierarchy = callHierarchyPayload!!,
            )

            // Phase 4: Demo playback
            runKotterDemoSession(
                presentation = presentationFor(report),
                terminalWidth = terminal.width,
                clearScreen = terminal::clear,
            )

            DemoFlowOutcome.Completed(
                DemoPlaybackResult(
                    report = report,
                    runtime = ensureResult!!,
                ),
            )
        }
    }

    internal fun presentationFor(report: DemoReport): KotterDemoSessionPresentation {
        val operations = listOf(
            referencesOperation(report),
            renameOperation(report),
            callersOperation(report),
        )
        return KotterDemoSessionPresentation(
            scenario = KotterDemoSessionScenario(
                initialOperationId = operations.first().id,
                operations = operations.map(DemoOperationPlayback::toScenario),
            ),
            operations = operations.map(DemoOperationPlayback::toPresentation),
        )
    }

    private fun referencesOperation(report: DemoReport): DemoOperationPlayback {
        val symbolName = report.resolvedSymbol.fqName
        val references = report.references.references
        return DemoOperationPlayback(
            id = "references",
            label = "Find References",
            shortcutKey = 'f',
            query = "kast references --symbol $symbolName",
            phases = listOf(
                DemoPhasePlayback(
                    id = "resolve",
                    lines = listOf(
                        tl("resolve ${report.resolvedSymbol.kind.name.lowercase()} $symbolName", KotterDemoStreamTone.COMMAND),
                        tl("declaration ${Paths.locationLine(report.workspaceRoot, report.resolvedSymbol.location)}", KotterDemoStreamTone.COMMAND),
                    ),
                ),
                DemoPhasePlayback(
                    id = "search",
                    lines = buildList {
                        add(tl("semantic references ${references.size}", KotterDemoStreamTone.CONFIRMED))
                        add(tl("grep baseline ${report.textSearch.totalMatches} matches / ${report.textSearch.falsePositives} false positives", KotterDemoStreamTone.FLAGGED))
                        references.take(REFERENCE_PREVIEW_LIMIT).forEach { reference ->
                            add(tl("${Paths.locationLine(report.workspaceRoot, reference)}  ${reference.preview.trim().take(LIVE_LINE_PREVIEW_LIMIT)}"))
                        }
                        if (references.size > REFERENCE_PREVIEW_LIMIT) {
                            add(tl("... and ${references.size - REFERENCE_PREVIEW_LIMIT} more semantic hits", KotterDemoStreamTone.STRUCTURE))
                        }
                    },
                ),
                DemoPhasePlayback(
                    id = "summarize",
                    lines = buildList {
                        report.references.searchScope?.let { scope ->
                            add(tl("scope ${scope.scope} exhaustive=${scope.exhaustive}", KotterDemoStreamTone.CONFIRMED))
                            add(tl("searched ${scope.searchedFileCount}/${scope.candidateFileCount} candidate files"))
                        } ?: add(tl("search scope unavailable", KotterDemoStreamTone.FLAGGED))
                        add(tl("declaration included ${report.references.declaration != null}", KotterDemoStreamTone.CONFIRMED))
                    },
                ),
            ),
        )
    }

    private fun renameOperation(report: DemoReport): DemoOperationPlayback {
        val symbolName = report.resolvedSymbol.fqName
        val renamed = "${report.resolvedSymbol.fqName.substringAfterLast('.')}Renamed"
        return DemoOperationPlayback(
            id = "rename",
            label = "Rename Dry Run",
            shortcutKey = 'n',
            query = "kast rename --symbol $symbolName --new-name $renamed --dry-run",
            branches = renameBranches(report),
            phases = listOf(
                DemoPhasePlayback(
                    id = "resolve",
                    lines = listOf(
                        tl("resolve rename target $symbolName", KotterDemoStreamTone.COMMAND),
                        tl("compare against grep touching ${report.textSearch.filesTouched} files blindly", KotterDemoStreamTone.FLAGGED),
                    ),
                ),
                DemoPhasePlayback(
                    id = "plan",
                    lines = buildList {
                        add(tl("dry run edits ${report.rename.edits.size}", KotterDemoStreamTone.CONFIRMED))
                        add(tl("affected files ${report.rename.affectedFiles.size}", KotterDemoStreamTone.CONFIRMED))
                        report.rename.affectedFiles.take(RENAME_FILE_PREVIEW_LIMIT).forEach { filePath ->
                            add(tl(Paths.relative(report.workspaceRoot, filePath)))
                        }
                        if (report.rename.affectedFiles.size > RENAME_FILE_PREVIEW_LIMIT) {
                            add(tl("... and ${report.rename.affectedFiles.size - RENAME_FILE_PREVIEW_LIMIT} more affected files", KotterDemoStreamTone.STRUCTURE))
                        }
                    },
                ),
                DemoPhasePlayback(
                    id = "verify",
                    lines = listOf(
                        tl("preimage hashes ${report.rename.fileHashes.size}", KotterDemoStreamTone.CONFIRMED),
                        tl("semantic plan avoids ${report.textSearch.falsePositives} grep false positives", KotterDemoStreamTone.CONFIRMED),
                    ),
                ),
            ),
        )
    }

    private fun callersOperation(report: DemoReport): DemoOperationPlayback {
        val symbolName = report.resolvedSymbol.fqName
        val callTree = renderCallTreePreview(report.workspaceRoot, report.callHierarchy.root)
        return DemoOperationPlayback(
            id = "callers",
            label = "Incoming Callers",
            shortcutKey = 'c',
            query = "kast call-hierarchy --symbol $symbolName --direction incoming --depth 2",
            phases = listOf(
                DemoPhasePlayback(
                    id = "resolve",
                    lines = listOf(
                        tl("resolve incoming-call target $symbolName", KotterDemoStreamTone.COMMAND),
                        tl("grep cannot recover caller identity from substrings alone", KotterDemoStreamTone.FLAGGED),
                    ),
                ),
                DemoPhasePlayback(
                    id = "walk",
                    lines = buildList {
                        add(tl("incoming callers ${report.callHierarchy.stats.totalNodes}", KotterDemoStreamTone.CONFIRMED))
                        callTree.take(CALL_TREE_PREVIEW_LIMIT).forEach { add(tl(it)) }
                        if (callTree.size > CALL_TREE_PREVIEW_LIMIT) {
                            add(tl("... and ${callTree.size - CALL_TREE_PREVIEW_LIMIT} more nodes", KotterDemoStreamTone.STRUCTURE))
                        }
                    },
                ),
                DemoPhasePlayback(
                    id = "summarize",
                    lines = buildList {
                        add(tl("max depth ${report.callHierarchy.stats.maxDepthReached}"))
                        add(tl("files visited ${report.callHierarchy.stats.filesVisited}"))
                        if (report.callHierarchy.stats.timeoutReached || report.callHierarchy.stats.maxTotalCallsReached) {
                            add(tl("results truncated before the full graph completed", KotterDemoStreamTone.FLAGGED))
                        } else {
                            add(tl("graph completed without backend truncation", KotterDemoStreamTone.CONFIRMED))
                        }
                    },
                ),
            ),
        )
    }

    private fun renameBranches(report: DemoReport): List<KotterDemoBranchSpec> {
        if (report.rename.affectedFiles.isEmpty()) return emptyList()

        val editsByFile = report.rename.edits.groupingBy { it.filePath }.eachCount()
        val hashedFiles = report.rename.fileHashes.mapTo(linkedSetOf(), FileHash::filePath)
        val visibleFiles = when {
            report.rename.affectedFiles.size <= RENAME_BRANCH_COLUMN_LIMIT -> report.rename.affectedFiles
            else -> report.rename.affectedFiles.take(RENAME_BRANCH_COLUMN_LIMIT - 1)
        }

        val visibleBranches = visibleFiles.map { filePath ->
            KotterDemoBranchSpec(
                header = Paths.fileName(filePath),
                lines = listOf(
                    "${editsByFile[filePath] ?: 0} planned edits",
                    if (filePath in hashedFiles) "hash guard ready" else "hash guard unavailable",
                ),
                summary = Paths.relative(report.workspaceRoot, filePath),
            )
        }

        val overflowCount = report.rename.affectedFiles.size - visibleFiles.size
        if (overflowCount <= 0) return visibleBranches

        val overflowFiles = report.rename.affectedFiles.drop(visibleFiles.size)
        val overflowEdits = overflowFiles.sumOf { filePath -> editsByFile[filePath] ?: 0 }
        return visibleBranches + KotterDemoBranchSpec(
            header = "+$overflowCount more",
            lines = listOf(
                "$overflowCount additional files",
                "$overflowEdits additional edits",
            ),
            summary = "dry-run output contains the full plan",
        )
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
        const val CALL_TREE_PREVIEW_LIMIT = 8
        const val LIVE_LINE_PREVIEW_LIMIT = 72
        const val REFERENCE_PREVIEW_LIMIT = 5
        const val RENAME_FILE_PREVIEW_LIMIT = 6
        const val RENAME_BRANCH_COLUMN_LIMIT = 3
        const val SCENARIO_LINE_DELAY_MILLIS = 90L
        const val SCENARIO_PHASE_DELAY_MILLIS = 150L
        const val SAMPLE_MATCH_LIMIT = 12
    }
}

/** Shorthand for building [KotterDemoTranscriptLine]s in the operation builders. */
private fun tl(
    text: String,
    tone: KotterDemoStreamTone = KotterDemoStreamTone.DETAIL,
): KotterDemoTranscriptLine = KotterDemoTranscriptLine(text, tone)

internal sealed interface DemoFlowOutcome {
    data class Completed(val result: DemoPlaybackResult) : DemoFlowOutcome
    data object Cancelled : DemoFlowOutcome
    data class Failed(val message: String) : DemoFlowOutcome
}

internal data class DemoPlaybackResult(
    val report: DemoReport,
    val runtime: WorkspaceEnsureResult,
)

internal fun interface KotterDemoSessionRunner {
    fun runSession(verbose: Boolean, block: Session.(terminal: Terminal) -> DemoFlowOutcome): DemoFlowOutcome
}

internal class LiveKotterDemoSessionRunner(
    private val terminalFactory: () -> Terminal = ::defaultKotterDemoTerminal,
) : KotterDemoSessionRunner {
    override fun runSession(verbose: Boolean, block: Session.(terminal: Terminal) -> DemoFlowOutcome): DemoFlowOutcome {
        val terminal = terminalFactory()
        var outcome: DemoFlowOutcome = DemoFlowOutcome.Cancelled
        session(terminal = terminal, clearTerminal = true) {
            outcome = block(terminal)
        }
        return outcome
    }
}

private fun defaultKotterDemoTerminal(): Terminal =
    runCatching { SystemTerminal() }
        .getOrElse { VirtualTerminal.create() }

private data class DemoOperationPlayback(
    val id: String,
    val label: String,
    val shortcutKey: Char,
    val query: String,
    val phases: List<DemoPhasePlayback>,
    val branches: List<KotterDemoBranchSpec> = emptyList(),
) {
    fun toScenario(): KotterDemoOperationScenario {
        var currentAt = 0L
        val events = buildList {
            phases.forEach { phase ->
                phase.lines.forEach { line ->
                    currentAt += DemoCommandSupport.SCENARIO_LINE_DELAY_MILLIS
                    add(KotterDemoScenarioEvent.Line(atMillis = currentAt, phaseId = phase.id, text = line.text, tone = line.tone))
                }
                currentAt += DemoCommandSupport.SCENARIO_PHASE_DELAY_MILLIS
                add(KotterDemoScenarioEvent.Milestone(atMillis = currentAt, phaseId = phase.id))
            }
        }
        return KotterDemoOperationScenario(
            id = id,
            phases = phases.map(DemoPhasePlayback::id),
            events = events,
        )
    }

    fun toPresentation(): KotterDemoOperationPresentation = KotterDemoOperationPresentation(
        id = id,
        label = label,
        shortcutKey = shortcutKey,
        query = query,
        branches = branches,
    )
}

private data class DemoPhasePlayback(
    val id: String,
    val lines: List<KotterDemoTranscriptLine>,
)

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
