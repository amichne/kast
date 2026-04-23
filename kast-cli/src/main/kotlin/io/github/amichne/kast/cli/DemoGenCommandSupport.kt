package io.github.amichne.kast.cli

import com.varabyte.kotter.foundation.input.CharKey
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.runtime.Session
import com.varabyte.kotter.runtime.terminal.Terminal
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.CallHierarchyQuery
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.RenameQuery
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolQuery
import io.github.amichne.kast.api.contract.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.demo.runLoadingPhase
import io.github.amichne.kast.demo.DemoGenScreen
import io.github.amichne.kast.demo.DualPaneConversation
import io.github.amichne.kast.demo.renderDemoGenScreen
import java.nio.file.Path

/**
 * Orchestrates `kast demo-gen`: clones a target repo, bootstraps a
 * standalone backend, curates interesting symbols, generates per-symbol
 * dual-pane reports, then renders or exports them.
 *
 * Mirrors [DemoCommandSupport.runInteractive] in spirit but reuses the
 * existing [DemoCommandSupport] for text-search analytics and the
 * existing [SymbolCurationEngine] for ranking.
 */
internal class DemoGenCommandSupport(
    private val backend: DemoGenBackend,
    private val demoSupport: DemoCommandSupport,
    private val curationEngine: SymbolCurationEngine,
    private val sessionRunner: KotterDemoSessionRunner = LiveKotterDemoSessionRunner(),
    private val ingestion: RepoIngestionPort = DefaultRepoIngestionPort,
    private val output: DemoGenOutput = StdoutDemoGenOutput,
) {
    suspend fun runInteractive(options: DemoGenOptions): DemoFlowOutcome {
        // Clone is synchronous I/O that throws CliFailure on bad URL or
        // failed clone; let those propagate unchanged so the CLI surfaces
        // them with their original error codes.
        val workspaceRoot = ingestion.clone(options.repoUrl)

        return when (options.output) {
            DemoGenOutputFormat.MARKDOWN, DemoGenOutputFormat.JSON -> runHeadless(options, workspaceRoot)
            DemoGenOutputFormat.TERMINAL -> runTerminal(options, workspaceRoot)
        }
    }

    private suspend fun runHeadless(options: DemoGenOptions, workspaceRoot: Path): DemoFlowOutcome {
        val ensure = try {
            ingestion.bootstrap(workspaceRoot, backend)
        } catch (failure: CliFailure) {
            throw failure
        } catch (t: Throwable) {
            return DemoFlowOutcome.Failed("Workspace bootstrap failed: ${t.message ?: t::class.simpleName}")
        }

        val symbols = try {
            backend.listAllSymbols(workspaceRoot)
        } catch (t: Throwable) {
            return DemoFlowOutcome.Failed("Workspace symbol listing failed: ${t.message ?: t::class.simpleName}")
        }

        val curated = curationEngine.curate(workspaceRoot, symbols, options.symbolCount)
        if (curated.isEmpty()) {
            return DemoFlowOutcome.Failed("No symbols selected by curation engine for ${options.repoUrl}")
        }

        val reports = try {
            curated.map { backend.buildReportInstrumented(workspaceRoot, it.symbol) { _, _ -> } }
        } catch (t: Throwable) {
            return DemoFlowOutcome.Failed("Per-symbol analysis failed: ${t.message ?: t::class.simpleName}")
        }

        val screen = buildScreen(reports)
        when (options.output) {
            DemoGenOutputFormat.MARKDOWN -> output.println(DemoGenMarkdownExporter.export(screen))
            DemoGenOutputFormat.JSON -> output.println(DemoGenJsonExporter.export(screen))
            DemoGenOutputFormat.TERMINAL -> Unit // Unreachable in this branch.
        }

        return DemoFlowOutcome.Completed(
            DemoPlaybackResult(
                report = reports.first(),
                runtime = ensure.selected,
                daemonNote = ensure.note,
            ),
        )
    }

    private suspend fun runTerminal(options: DemoGenOptions, workspaceRoot: Path): DemoFlowOutcome {
        return sessionRunner.runSession(options.verbose) { terminal ->
            // ── Phase A: clone + bootstrap + symbol indexing ────────────
            var ensureResult: WorkspaceEnsureResult? = null
            var allSymbols: List<Symbol> = emptyList()
            val phaseAOk = runLoadingPhase(
                symbolName = repoLabel(options.repoUrl),
                steps = listOf("Cloning repository", "Bootstrapping workspace", "Indexing symbols"),
            ) { onStepComplete ->
                // Step 0 — clone already happened upfront; record it as instant.
                onStepComplete(0, 0L)

                val t1 = System.currentTimeMillis()
                ensureResult = ingestion.bootstrap(workspaceRoot, backend)
                onStepComplete(1, System.currentTimeMillis() - t1)

                val t2 = System.currentTimeMillis()
                allSymbols = backend.listAllSymbols(workspaceRoot)
                onStepComplete(2, System.currentTimeMillis() - t2)
            }
            if (!phaseAOk) return@runSession DemoFlowOutcome.Failed("Workspace bootstrap failed")
            val ensure = ensureResult ?: return@runSession DemoFlowOutcome.Failed("Workspace bootstrap returned no runtime")

            // ── Phase B: curation ───────────────────────────────────────
            var curated: List<CuratedSymbol> = emptyList()
            val phaseBOk = runLoadingPhase(
                symbolName = repoLabel(options.repoUrl),
                steps = listOf("Scanning symbols", "Scoring contrast", "Selecting top ${options.symbolCount}"),
            ) { onStepComplete ->
                val t0 = System.currentTimeMillis()
                onStepComplete(0, System.currentTimeMillis() - t0)

                val t1 = System.currentTimeMillis()
                curated = curationEngine.curate(workspaceRoot, allSymbols, options.symbolCount)
                onStepComplete(1, System.currentTimeMillis() - t1)

                onStepComplete(2, 0L)
            }
            if (!phaseBOk) return@runSession DemoFlowOutcome.Failed("Curation failed")
            if (curated.isEmpty()) return@runSession DemoFlowOutcome.Failed("No symbols selected by curation engine")

            // ── Phase C: per-symbol analysis ────────────────────────────
            val reports = mutableListOf<DemoReport>()
            for (entry in curated) {
                var report: DemoReport? = null
                val ok = runLoadingPhase(
                    symbolName = entry.simpleName,
                    steps = listOf("Resolve symbol", "Find references", "Rename dry-run", "Call hierarchy", "Text search"),
                ) { onStepComplete ->
                    report = backend.buildReportInstrumented(workspaceRoot, entry.symbol, onStepComplete)
                }
                if (!ok || report == null) {
                    return@runSession DemoFlowOutcome.Failed("Per-symbol analysis failed for ${entry.simpleName}")
                }
                reports += report!!
            }

            // ── Phase D: presentation ───────────────────────────────────
            val screen = buildScreen(reports)
            renderInteractiveScreen(terminal, screen)

            DemoFlowOutcome.Completed(
                DemoPlaybackResult(
                    report = reports.first(),
                    runtime = ensure.selected,
                    daemonNote = ensure.note,
                ),
            )
        }
    }

    private fun buildScreen(reports: List<DemoReport>): DemoGenScreen {
        val conversations: List<DualPaneConversation> = reports.map { ConversationTemplateEngine.build(it) }
        return DemoGenScreen(conversations = conversations, activeIndex = 0)
    }

    /**
     * Interactive Kotter loop: redraws the current screen on each switch/replay
     * and breaks out on quit. Scroll keys are wired into local state but the
     * renderer does not currently consume an offset, so they are no-ops for now.
     */
    private fun Session.renderInteractiveScreen(terminal: Terminal, initial: DemoGenScreen) {
        var screen by liveVarOf(initial)
        @Suppress("UNUSED_VARIABLE")
        var scrollOffset by liveVarOf(0)
        val panelWidth = (terminal.width - 4).coerceAtLeast(60).coerceAtMost(120)

        section {
            renderDemoGenScreen(screen, panelWidth)
        }.runUntilSignal {
            onKeyPressed {
                when (val pressed = key) {
                    Keys.Q, Keys.ESC, CharKey('q'), CharKey('Q') -> signal()
                    Keys.UP -> { scrollOffset = (scrollOffset - 1).coerceAtLeast(0) }
                    Keys.DOWN -> { scrollOffset += 1 }
                    CharKey('r'), CharKey('R') -> {
                        // Re-emit the current screen to force a redraw.
                        screen = screen.copy()
                    }
                    is CharKey -> {
                        val ch = pressed.code
                        if (ch in '1'..'9') {
                            val idx = ch.digitToInt() - 1
                            if (idx in screen.conversations.indices) {
                                screen = screen.copy(activeIndex = idx)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun repoLabel(repoUrl: String): String =
        repoUrl.substringAfterLast('/').removeSuffix(".git").ifBlank { repoUrl }
}

/**
 * Indirection over [RepoIngestion] so tests can inject a fake clone/bootstrap
 * pair without touching the filesystem or shelling out to git.
 */
internal interface RepoIngestionPort {
    fun clone(repoUrl: String): Path
    suspend fun bootstrap(workspaceRoot: Path, backend: DemoGenBackend): WorkspaceEnsureResult
}

internal object DefaultRepoIngestionPort : RepoIngestionPort {
    override fun clone(repoUrl: String): Path = RepoIngestion.clone(repoUrl)
    override suspend fun bootstrap(workspaceRoot: Path, backend: DemoGenBackend): WorkspaceEnsureResult =
        backend.bootstrap(workspaceRoot)
}

/**
 * Backend operations consumed by [DemoGenCommandSupport]. Production wires
 * this to [CliService]; tests inject an in-memory fake to avoid spawning a
 * real daemon.
 */
internal interface DemoGenBackend {
    suspend fun bootstrap(workspaceRoot: Path): WorkspaceEnsureResult
    suspend fun listAllSymbols(workspaceRoot: Path): List<Symbol>
    suspend fun buildReportInstrumented(
        workspaceRoot: Path,
        symbol: Symbol,
        onStepComplete: (index: Int, durationMs: Long) -> Unit,
    ): DemoReport
}

/** Production [DemoGenBackend] that delegates to [CliService] and [DemoCommandSupport]. */
internal class CliServiceDemoGenBackend(
    private val cliService: CliService,
    private val demoSupport: DemoCommandSupport,
) : DemoGenBackend {
    override suspend fun bootstrap(workspaceRoot: Path): WorkspaceEnsureResult =
        RepoIngestion.bootstrap(workspaceRoot, cliService)

    override suspend fun listAllSymbols(workspaceRoot: Path): List<Symbol> {
        val root = workspaceRoot.toAbsolutePath().normalize()
        return cliService.workspaceSymbolSearch(
            runtimeOptionsFor(workspaceRoot),
            WorkspaceSymbolQuery(pattern = ".", maxResults = 500, regex = true),
        ).payload.symbols.filter { symbol ->
            runCatching {
                java.nio.file.Paths.get(symbol.location.filePath)
                    .toAbsolutePath()
                    .normalize()
                    .startsWith(root)
            }.getOrDefault(false)
        }
    }

    override suspend fun buildReportInstrumented(
        workspaceRoot: Path,
        symbol: Symbol,
        onStepComplete: (index: Int, durationMs: Long) -> Unit,
    ): DemoReport {
        val runtimeOptions = runtimeOptionsFor(workspaceRoot)
        val symbolPosition = FilePosition(
            filePath = symbol.location.filePath,
            offset = symbol.location.startOffset,
        )

        val t0 = System.currentTimeMillis()
        val resolved = cliService.resolveSymbol(runtimeOptions, SymbolQuery(position = symbolPosition))
            .payload.symbol
        onStepComplete(0, System.currentTimeMillis() - t0)

        val t1 = System.currentTimeMillis()
        val references = cliService.findReferences(
            runtimeOptions,
            ReferencesQuery(position = symbolPosition, includeDeclaration = true),
        ).payload
        onStepComplete(1, System.currentTimeMillis() - t1)

        val t2 = System.currentTimeMillis()
        val rename = cliService.rename(
            runtimeOptions,
            RenameQuery(
                position = symbolPosition,
                newName = "${resolved.fqName.substringAfterLast('.')}Renamed",
                dryRun = true,
            ),
        ).payload
        onStepComplete(2, System.currentTimeMillis() - t2)

        val t3 = System.currentTimeMillis()
        val callHierarchy = cliService.callHierarchy(
            runtimeOptions,
            CallHierarchyQuery(position = symbolPosition, direction = CallDirection.INCOMING, depth = 2),
        ).payload
        onStepComplete(3, System.currentTimeMillis() - t3)

        val t4 = System.currentTimeMillis()
        val textSearch = demoSupport.analyzeTextSearch(workspaceRoot, symbol)
        onStepComplete(4, System.currentTimeMillis() - t4)

        return DemoReport(
            workspaceRoot = workspaceRoot,
            selectedSymbol = symbol,
            textSearch = textSearch,
            resolvedSymbol = resolved,
            references = references,
            rename = rename,
            callHierarchy = callHierarchy,
        )
    }

    private fun runtimeOptionsFor(workspaceRoot: Path): RuntimeCommandOptions =
        RuntimeCommandOptions(
            workspaceRoot = workspaceRoot,
            backendName = "standalone",
            waitTimeoutMillis = 180_000L,
        )
}

/** Stdout sink — split so MARKDOWN/JSON output can be captured in tests. */
internal fun interface DemoGenOutput {
    fun println(text: String)
}

internal object StdoutDemoGenOutput : DemoGenOutput {
    override fun println(text: String) {
        kotlin.io.println(text)
    }
}
