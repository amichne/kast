package io.github.amichne.kast.cli

import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.runtime.Session
import com.varabyte.kotter.runtime.terminal.Terminal
import com.varabyte.kotter.terminal.system.SystemTerminal
import com.varabyte.kotter.terminal.virtual.VirtualTerminal
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
import io.github.amichne.kast.api.contract.SymbolResult
import io.github.amichne.kast.api.contract.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.demo.KotterDemoLayoutCalculator
import io.github.amichne.kast.cli.demo.KotterDemoLayoutDecision
import io.github.amichne.kast.cli.demo.KotterDemoLayoutMode
import io.github.amichne.kast.cli.demo.KotterDemoLayoutRequest
import io.github.amichne.kast.cli.demo.KotterDemoOperationScenario
import io.github.amichne.kast.cli.demo.KotterDemoScenarioEvent
import io.github.amichne.kast.cli.demo.KotterDemoSessionPresentation
import io.github.amichne.kast.cli.demo.KotterDemoSessionScenario
import io.github.amichne.kast.cli.demo.SymbolPickerResult
import io.github.amichne.kast.cli.demo.buildDualPaneScenario
import io.github.amichne.kast.cli.demo.loadCapture
import io.github.amichne.kast.cli.demo.runDualPaneSession
import io.github.amichne.kast.cli.demo.runKotterDemoSession
import io.github.amichne.kast.cli.demo.runLoadingPhase
import io.github.amichne.kast.cli.demo.runSymbolPicker
import java.io.Console
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Shared entry points used by [CliService.demo] and directly by unit tests. */
internal open class DemoCommandSupport(
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

    // `open` so test seams (e.g. SymbolCurationEngineTest) can subclass and stub the filesystem walk.
    open fun analyzeTextSearch(
        workspaceRoot: Path,
        symbol: Symbol,
    ): DemoTextSearchSummary = WorkspaceTextIndex(workspaceRoot).analyze(symbol)

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
            options.fixture?.let { fixturePath ->
                val capture = loadCapture(fixturePath)
                val decision = dualPaneLayoutDecision(terminal.width)
                when (decision) {
                    is KotterDemoLayoutDecision.Ready -> {
                        val dualPaneLayout = decision.dualPane
                            ?: return@runSession DemoFlowOutcome.Failed("Terminal width ${terminal.width} is too narrow for fixture replay; need at least 120 columns.")
                        runDualPaneSession(capture.scenario, dualPaneLayout)
                        return@runSession DemoFlowOutcome.Completed(
                            DemoPlaybackResult.RenderOnly,
                        )
                    }
                    is KotterDemoLayoutDecision.Halted -> return@runSession DemoFlowOutcome.Failed(decision.warning)
                    else -> return@runSession DemoFlowOutcome.Failed("Unexpected dual-pane layout decision: $decision")
                }
            }

            // Phase 1+2: Warm backend + symbol picker (combined in runSymbolPicker)
            val pickerResult = runSymbolPicker(
                verbose = options.verbose,
                searchSymbols = { query ->
                    cliService.workspaceSymbolSearch(runtimeOptions, query).payload
                },
                warmBackend = {
                    // Best-effort: start the daemon while the user browses.
                    // The result is intentionally discarded — we capture the
                    // authoritative WorkspaceEnsureResult from the first
                    // RuntimeAttachedResult in the loading phase instead,
                    // which avoids a race where signal() cancels this
                    // coroutine before the assignment can land.
                    cliService.workspaceEnsure(runtimeOptions)
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

            // Phase 3: Load demo data with progress.
            // The first CliService call (resolveSymbol) returns a
            // RuntimeAttachedResult that carries the authoritative runtime
            // metadata — we capture it here instead of relying on the
            // fire-and-forget warmBackend coroutine that may have been
            // cancelled when the picker section ended.
            val textIndex = WorkspaceTextIndex(options.workspaceRoot)
            val loadResult = loadDemoData(
                selectedSymbol = selectedSymbol,
                symbolPosition = symbolPosition,
                runtimeOptions = runtimeOptions,
                client = CliServiceDemoLoadingClient(cliService),
                textSearchOf = textIndex::analyze,
                runLoading = { executeSteps ->
                    runLoadingPhase(
                        symbolName = selectedSymbol.fqName.substringAfterLast('.'),
                        steps = listOf("Resolve symbol", "Find references", "Rename dry-run", "Call hierarchy", "Text search"),
                        executeSteps = executeSteps,
                    )
                },
            )

            if (loadResult !is DemoLoadResult.Completed) {
                return@runSession DemoFlowOutcome.Failed("Backend queries failed")
            }

            val report = DemoReport(
                workspaceRoot = options.workspaceRoot,
                selectedSymbol = selectedSymbol,
                textSearch = loadResult.textSearch,
                resolvedSymbol = loadResult.resolvedSymbol,
                references = loadResult.references,
                rename = loadResult.rename,
                callHierarchy = loadResult.callHierarchy,
            )

            // Phase 4: Demo playback
            val layoutDecision = dualPaneLayoutDecision(terminal.width)
            when (layoutDecision) {
                is KotterDemoLayoutDecision.Ready -> {
                    val dualPane = layoutDecision.dualPane
                    if (dualPane != null) {
                        val scenario = buildDualPaneScenario(
                            report = report,
                            textSearchSummary = report.textSearch,
                            workspaceRoot = options.workspaceRoot,
                            verbose = options.verbose,
                            textSearchOf = { callerName -> textIndex.analyze(symbolForTextSearch(callerName, selectedSymbol)) },
                        )
                        runDualPaneSession(scenario, dualPane)
                    } else {
                        runKotterDemoSession(
                            presentation = presentationFor(report, verbose = options.verbose),
                            terminalWidth = terminal.width,
                            clearScreen = terminal::clear,
                        )
                    }
                }
                is KotterDemoLayoutDecision.Halted -> runKotterDemoSession(
                    presentation = presentationFor(report, verbose = options.verbose),
                    terminalWidth = terminal.width,
                    clearScreen = terminal::clear,
                )
                else -> runKotterDemoSession(
                    presentation = presentationFor(report, verbose = options.verbose),
                    terminalWidth = terminal.width,
                    clearScreen = terminal::clear,
                )
            }

            DemoFlowOutcome.Completed(
                DemoPlaybackResult.Full(
                    report = report,
                    runtime = loadResult.runtimeStatus,
                    daemonNote = loadResult.daemonNote,
                ),
            )
        }
    }

    private fun dualPaneLayoutDecision(terminalWidth: Int): KotterDemoLayoutDecision =
        KotterDemoLayoutCalculator().layout(
            KotterDemoLayoutRequest(
                terminalWidth = terminalWidth,
                operations = listOf("References", "Rename", "Call Graph"),
                activeOperation = "References",
                query = "kast demo",
                cursorVisible = false,
                mode = KotterDemoLayoutMode.DualPane,
            ),
        )

    private fun symbolForTextSearch(name: String, fallback: Symbol): Symbol =
        fallback.copy(fqName = name)

    internal fun presentationFor(report: DemoReport, verbose: Boolean = true): KotterDemoSessionPresentation =
        DemoPresentationBuilder().build(report, verbose)

    private fun symbolMatchesFilter(
        symbol: Symbol,
        filter: String,
    ): Boolean {
        val simpleName = symbol.fqName.substringAfterLast('.')
        return symbol.fqName == filter || simpleName == filter || symbol.fqName.endsWith(".$filter")
    }

}

internal sealed interface DemoFlowOutcome {
    data class Completed(val result: DemoPlaybackResult) : DemoFlowOutcome
    data object Cancelled : DemoFlowOutcome
    data class Failed(val message: String) : DemoFlowOutcome
}

internal sealed interface DemoPlaybackResult {
    data class Full(
        val report: DemoReport,
        val runtime: RuntimeCandidateStatus,
        val daemonNote: String? = null,
    ) : DemoPlaybackResult

    data object RenderOnly : DemoPlaybackResult
}

internal sealed interface DemoLoadResult {
    data class Completed(
        val resolvedSymbol: Symbol,
        val references: ReferencesResult,
        val rename: RenameResult,
        val callHierarchy: CallHierarchyResult,
        val textSearch: DemoTextSearchSummary,
        val runtimeStatus: RuntimeCandidateStatus,
        val daemonNote: String?,
    ) : DemoLoadResult

    data object Failed : DemoLoadResult
}

internal typealias DemoLoadingStepComplete = (index: Int, durationMs: Long) -> Unit

internal interface DemoLoadingClient {
    suspend fun resolveSymbol(
        options: RuntimeCommandOptions,
        query: SymbolQuery,
    ): RuntimeAttachedResult<SymbolResult>

    suspend fun findReferences(
        options: RuntimeCommandOptions,
        query: ReferencesQuery,
    ): RuntimeAttachedResult<ReferencesResult>

    suspend fun rename(
        options: RuntimeCommandOptions,
        query: RenameQuery,
    ): RuntimeAttachedResult<RenameResult>

    suspend fun callHierarchy(
        options: RuntimeCommandOptions,
        query: CallHierarchyQuery,
    ): RuntimeAttachedResult<CallHierarchyResult>
}

private class CliServiceDemoLoadingClient(
    private val cliService: CliService,
) : DemoLoadingClient {
    override suspend fun resolveSymbol(
        options: RuntimeCommandOptions,
        query: SymbolQuery,
    ): RuntimeAttachedResult<SymbolResult> = cliService.resolveSymbol(options, query)

    override suspend fun findReferences(
        options: RuntimeCommandOptions,
        query: ReferencesQuery,
    ): RuntimeAttachedResult<ReferencesResult> = cliService.findReferences(options, query)

    override suspend fun rename(
        options: RuntimeCommandOptions,
        query: RenameQuery,
    ): RuntimeAttachedResult<RenameResult> = cliService.rename(options, query)

    override suspend fun callHierarchy(
        options: RuntimeCommandOptions,
        query: CallHierarchyQuery,
    ): RuntimeAttachedResult<CallHierarchyResult> = cliService.callHierarchy(options, query)
}

internal fun loadDemoData(
    selectedSymbol: Symbol,
    symbolPosition: FilePosition,
    runtimeOptions: RuntimeCommandOptions,
    client: DemoLoadingClient,
    textSearchOf: (Symbol) -> DemoTextSearchSummary,
    runLoading: ((suspend (DemoLoadingStepComplete) -> Unit) -> Boolean),
): DemoLoadResult {
    var completedResult: DemoLoadResult.Completed? = null
    val loaded = runLoading { onStepComplete ->
        val t0 = System.currentTimeMillis()
        val resolveResult = client.resolveSymbol(
            runtimeOptions,
            SymbolQuery(position = symbolPosition),
        )
        val resolvedSymbol = resolveResult.payload.symbol
        onStepComplete(0, System.currentTimeMillis() - t0)

        val (references, rename, callHierarchy, textSearch) = coroutineScope {
            val referencesDeferred = async {
                val t1 = System.currentTimeMillis()
                val payload = client.findReferences(
                    runtimeOptions,
                    ReferencesQuery(position = symbolPosition, includeDeclaration = true),
                ).payload
                onStepComplete(1, System.currentTimeMillis() - t1)
                payload
            }

            val renameDeferred = async {
                val t2 = System.currentTimeMillis()
                val payload = client.rename(
                    runtimeOptions,
                    RenameQuery(
                        position = symbolPosition,
                        newName = "${resolvedSymbol.fqName.substringAfterLast('.')}Renamed",
                        dryRun = true,
                    ),
                ).payload
                onStepComplete(2, System.currentTimeMillis() - t2)
                payload
            }

            val callHierarchyDeferred = async {
                val t3 = System.currentTimeMillis()
                val payload = client.callHierarchy(
                    runtimeOptions,
                    CallHierarchyQuery(
                        position = symbolPosition,
                        direction = CallDirection.INCOMING,
                        depth = 2,
                    ),
                ).payload
                onStepComplete(3, System.currentTimeMillis() - t3)
                payload
            }

            val textSearchDeferred = async {
                val t4 = System.currentTimeMillis()
                val payload = textSearchOf(selectedSymbol)
                onStepComplete(4, System.currentTimeMillis() - t4)
                payload
            }

            DemoLoadedQueries(
                references = referencesDeferred.await(),
                rename = renameDeferred.await(),
                callHierarchy = callHierarchyDeferred.await(),
                textSearch = textSearchDeferred.await(),
            )
        }

        completedResult = DemoLoadResult.Completed(
            resolvedSymbol = resolvedSymbol,
            references = references,
            rename = rename,
            callHierarchy = callHierarchy,
            textSearch = textSearch,
            runtimeStatus = resolveResult.runtime,
            daemonNote = resolveResult.daemonNote,
        )
    }

    return if (loaded) {
        checkNotNull(completedResult) { "Loading completed without producing demo data" }
    } else {
        DemoLoadResult.Failed
    }
}

private data class DemoLoadedQueries(
    val references: ReferencesResult,
    val rename: RenameResult,
    val callHierarchy: CallHierarchyResult,
    val textSearch: DemoTextSearchSummary,
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

@kotlinx.serialization.Serializable
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
