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
import io.github.amichne.kast.cli.demo.runLoadingPhase
import io.github.amichne.kast.demo.DemoGenScreen
import io.github.amichne.kast.demo.DualPaneConversation
import io.github.amichne.kast.demo.renderDemoGenScreen
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Orchestrates `kast demo generate` and `kast demo render`:
 *
 * - Local mode: skips clone; uses the provided/current workspace, auto-selects
 *   backend (so IntelliJ is preferred when available).
 * - Remote mode: clones the target repo, indexes it via the standalone backend,
 *   and writes a JSON artifact to `<cwd>/.kast/demo-generate/` so it survives
 *   temp-dir cleanup.
 * - Progressive: per-symbol failures do not abort the run; failures are recorded
 *   and the artifact is updated after each symbol.
 * - Always saves a JSON artifact; the artifact is renderable even if only
 *   partially populated.
 */
internal class DemoGenCommandSupport(
    private val backend: DemoGenBackend,
    private val curationEngine: SymbolCurationEngine,
    private val sessionRunner: KotterDemoSessionRunner = LiveKotterDemoSessionRunner(),
    private val ingestion: RepoIngestionPort = DefaultRepoIngestionPort,
    private val output: DemoGenOutput = StdoutDemoGenOutput,
    private val workingDirectory: Path = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize(),
) {
    suspend fun runInteractive(options: DemoGenOptions): DemoFlowOutcome {
        // ── Workspace preparation ─────────────────────────────────────────────
        // Local mode: skip clone and use the provided/current workspace.
        // Remote mode: clone into a temp dir (cleanup remains registered by
        // RepoIngestion; artifact is written to cwd so it survives cleanup).
        val workspaceRoot: Path = if (options.local) {
            options.workspaceRoot ?: workingDirectory
        } else {
            // repoUrl is guaranteed non-null for remote mode by the parser.
            ingestion.clone(options.repoUrl!!)
        }

        // Artifact lives under workspaceRoot for local mode; under cwd for
        // remote mode (so it survives temp-dir cleanup on JVM exit).
        val artifactDir = (if (options.local) workspaceRoot else workingDirectory)
            .resolve(".kast/demo-generate")

        val generatedAt = Instant.now().atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val artifactPath = artifactDir.resolve("demo-$generatedAt.json")

        return when (options.output) {
            DemoGenOutputFormat.MARKDOWN, DemoGenOutputFormat.JSON ->
                runHeadless(options, workspaceRoot, artifactPath, generatedAt)
            DemoGenOutputFormat.TERMINAL ->
                runTerminal(options, workspaceRoot, artifactPath, generatedAt)
        }
    }

    /** Render a previously saved artifact JSON in a Kotter terminal session. */
    fun renderFromFile(jsonFile: Path, verbose: Boolean = false): DemoFlowOutcome {
        val screen = DemoGenJsonExporter.importScreen(jsonFile.readText())
        return sessionRunner.runSession(verbose) { terminal ->
            renderInteractiveScreen(terminal, screen)
            DemoFlowOutcome.Completed(DemoPlaybackResult.RenderOnly)
        }
    }

    // ── Headless path (JSON / Markdown output) ───────────────────────────────

    private suspend fun runHeadless(
        options: DemoGenOptions,
        workspaceRoot: Path,
        artifactPath: Path,
        generatedAt: String,
    ): DemoFlowOutcome {
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
            return DemoFlowOutcome.Failed("No symbols selected by curation engine for ${workspaceLabel(options, workspaceRoot)}")
        }

        val reports = mutableListOf<DemoReport>()
        val failures = mutableListOf<SymbolFailure>()

        for (entry in curated) {
            try {
                val report = backend.buildReportInstrumented(workspaceRoot, entry.symbol) { _, _ -> }
                reports += report
            } catch (t: Throwable) {
                failures += SymbolFailure(
                    symbol = entry.simpleName,
                    reason = t.message ?: t::class.simpleName ?: "unknown",
                )
            }
            // Progressive: persist after every symbol so partial results survive failure.
            writeArtifact(
                artifactPath,
                DemoGenArtifact(
                    screen = buildScreen(reports),
                    generatedAt = generatedAt,
                    status = if (reports.isEmpty()) DemoGenArtifactStatus.IN_PROGRESS else DemoGenArtifactStatus.PARTIAL,
                    workspaceRoot = workspaceRoot.toString(),
                    repoUrl = options.repoUrl,
                    failures = failures,
                ),
            )
        }

        if (reports.isEmpty()) {
            writeArtifact(
                artifactPath,
                DemoGenArtifact(
                    screen = buildScreen(emptyList()),
                    generatedAt = generatedAt,
                    status = DemoGenArtifactStatus.FAILED,
                    workspaceRoot = workspaceRoot.toString(),
                    repoUrl = options.repoUrl,
                    failures = failures,
                ),
            )
            return DemoFlowOutcome.Failed(
                "All per-symbol analyses failed for ${workspaceLabel(options, workspaceRoot)}. " +
                    "Artifact saved to $artifactPath",
            )
        }

        val screen = buildScreen(reports)
        val finalStatus = if (failures.isEmpty()) DemoGenArtifactStatus.COMPLETED else DemoGenArtifactStatus.PARTIAL
        writeArtifact(
            artifactPath,
            DemoGenArtifact(
                screen = screen,
                generatedAt = generatedAt,
                status = finalStatus,
                workspaceRoot = workspaceRoot.toString(),
                repoUrl = options.repoUrl,
                failures = failures,
            ),
        )

        when (options.output) {
            DemoGenOutputFormat.MARKDOWN -> output.println(DemoGenMarkdownExporter.export(screen))
            DemoGenOutputFormat.JSON -> output.println(DemoGenJsonExporter.exportArtifact(
                DemoGenArtifact(
                    screen = screen,
                    generatedAt = generatedAt,
                    status = finalStatus,
                    workspaceRoot = workspaceRoot.toString(),
                    repoUrl = options.repoUrl,
                    failures = failures,
                ),
            ))
            DemoGenOutputFormat.TERMINAL -> Unit
        }

        return DemoFlowOutcome.Completed(
            DemoPlaybackResult.Full(
                report = reports.first(),
                runtime = ensure.selected,
                daemonNote = ensure.note,
            ),
        )
    }

    // ── Terminal path ────────────────────────────────────────────────────────

    private suspend fun runTerminal(
        options: DemoGenOptions,
        workspaceRoot: Path,
        artifactPath: Path,
        generatedAt: String,
    ): DemoFlowOutcome {
        return sessionRunner.runSession(options.verbose) { terminal ->
            // ── Phase A: bootstrap + symbol indexing ─────────────────────────
            var ensureResult: WorkspaceEnsureResult? = null
            var allSymbols: List<Symbol> = emptyList()
            val phaseAOk = runLoadingPhase(
                symbolName = workspaceLabel(options, workspaceRoot),
                steps = if (options.local) {
                    listOf("Connecting to workspace", "Bootstrapping backend", "Indexing symbols")
                } else {
                    listOf("Cloning repository", "Bootstrapping workspace", "Indexing symbols")
                },
            ) { onStepComplete ->
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

            // ── Phase B: curation ────────────────────────────────────────────
            var curated: List<CuratedSymbol> = emptyList()
            val phaseBOk = runLoadingPhase(
                symbolName = workspaceLabel(options, workspaceRoot),
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

            // ── Phase C: per-symbol analysis (progressive, failure-tolerant) ─
            val reports = mutableListOf<DemoReport>()
            val failures = mutableListOf<SymbolFailure>()

            for (entry in curated) {
                var report: DemoReport? = null
                val ok = runLoadingPhase(
                    symbolName = entry.simpleName,
                    steps = listOf("Resolve symbol", "Find references", "Rename dry-run", "Call hierarchy", "Text search"),
                ) { onStepComplete ->
                    report = backend.buildReportInstrumented(workspaceRoot, entry.symbol, onStepComplete)
                }
                if (!ok || report == null) {
                    failures += SymbolFailure(entry.simpleName, "analysis phase failed")
                } else {
                    reports += report
                }
                // Persist progressive state after each symbol.
                writeArtifact(
                    artifactPath,
                    DemoGenArtifact(
                        screen = buildScreen(reports),
                        generatedAt = generatedAt,
                        status = DemoGenArtifactStatus.IN_PROGRESS,
                        workspaceRoot = workspaceRoot.toString(),
                        repoUrl = options.repoUrl,
                        failures = failures,
                    ),
                )
            }

            if (reports.isEmpty()) {
                writeArtifact(
                    artifactPath,
                    DemoGenArtifact(
                        screen = buildScreen(emptyList()),
                        generatedAt = generatedAt,
                        status = DemoGenArtifactStatus.FAILED,
                        workspaceRoot = workspaceRoot.toString(),
                        repoUrl = options.repoUrl,
                        failures = failures,
                    ),
                )
                return@runSession DemoFlowOutcome.Failed(
                    "All per-symbol analyses failed. Artifact saved to $artifactPath",
                )
            }

            // ── Phase D: presentation ────────────────────────────────────────
            val screen = buildScreen(reports)
            val finalStatus = if (failures.isEmpty()) DemoGenArtifactStatus.COMPLETED else DemoGenArtifactStatus.PARTIAL
            writeArtifact(
                artifactPath,
                DemoGenArtifact(
                    screen = screen,
                    generatedAt = generatedAt,
                    status = finalStatus,
                    workspaceRoot = workspaceRoot.toString(),
                    repoUrl = options.repoUrl,
                    failures = failures,
                ),
            )
            renderInteractiveScreen(terminal, screen)

            DemoFlowOutcome.Completed(
                DemoPlaybackResult.Full(
                    report = reports.first(),
                    runtime = ensure.selected,
                    daemonNote = ensure.note,
                ),
            )
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun buildScreen(reports: List<DemoReport>): DemoGenScreen {
        val conversations: List<DualPaneConversation> = reports.map { ConversationTemplateEngine.build(it) }
        return DemoGenScreen(conversations = conversations, activeIndex = 0)
    }

    private fun writeArtifact(artifactPath: Path, artifact: DemoGenArtifact) {
        runCatching {
            Files.createDirectories(artifactPath.parent)
            artifactPath.writeText(DemoGenJsonExporter.exportArtifact(artifact))
        }
    }

    /**
     * Interactive Kotter loop: redraws the current screen on each switch/replay
     * and breaks out on quit.
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

    private fun workspaceLabel(options: DemoGenOptions, workspaceRoot: Path): String =
        options.repoUrl
            ?.substringAfterLast('/')
            ?.removeSuffix(".git")
            ?.ifBlank { options.repoUrl }
            ?: workspaceRoot.fileName?.toString()
            ?: "local workspace"
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

/** Production [DemoGenBackend] that delegates to [CliService] and shared text search. */
internal class CliServiceDemoGenBackend(
    private val cliService: CliService,
    private val textSearchAnalyzer: TextSearchAnalyzer = WorkspaceTextSearchAnalyzer(),
    private val backendName: String? = "standalone",
    private val acceptIndexing: Boolean = false,
    private val loadingClient: DemoLoadingClient = CliServiceDemoGenLoadingClient(cliService),
) : DemoGenBackend {
    override suspend fun bootstrap(workspaceRoot: Path): WorkspaceEnsureResult =
        RepoIngestion.bootstrap(workspaceRoot, cliService, backendName = backendName, acceptIndexing = acceptIndexing)

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
        val resolved = loadingClient.resolveSymbol(runtimeOptions, SymbolQuery(position = symbolPosition))
            .payload.symbol
        onStepComplete(0, System.currentTimeMillis() - t0)

        val (references, rename, callHierarchy, textSearch) = coroutineScope {
            val referencesDeferred = async {
                val t1 = System.currentTimeMillis()
                val payload = loadingClient.findReferences(
                    runtimeOptions,
                    ReferencesQuery(position = symbolPosition, includeDeclaration = true),
                ).payload
                onStepComplete(1, System.currentTimeMillis() - t1)
                payload
            }

            val renameDeferred = async {
                val t2 = System.currentTimeMillis()
                val payload = loadingClient.rename(
                    runtimeOptions,
                    RenameQuery(
                        position = symbolPosition,
                        newName = "${resolved.fqName.substringAfterLast('.')}Renamed",
                        dryRun = true,
                    ),
                ).payload
                onStepComplete(2, System.currentTimeMillis() - t2)
                payload
            }

            val callHierarchyDeferred = async {
                val t3 = System.currentTimeMillis()
                val payload = loadingClient.callHierarchy(
                    runtimeOptions,
                    CallHierarchyQuery(position = symbolPosition, direction = CallDirection.INCOMING, depth = 2),
                ).payload
                onStepComplete(3, System.currentTimeMillis() - t3)
                payload
            }

            val textSearchDeferred = async {
                val t4 = System.currentTimeMillis()
                val payload = textSearchAnalyzer.analyze(workspaceRoot, symbol)
                onStepComplete(4, System.currentTimeMillis() - t4)
                payload
            }

            DemoGenLoadedQueries(
                references = referencesDeferred.await(),
                rename = renameDeferred.await(),
                callHierarchy = callHierarchyDeferred.await(),
                textSearch = textSearchDeferred.await(),
            )
        }

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
            backendName = backendName,
            waitTimeoutMillis = 180_000L,
            acceptIndexing = acceptIndexing,
        )
}

private data class DemoGenLoadedQueries(
    val references: ReferencesResult,
    val rename: RenameResult,
    val callHierarchy: CallHierarchyResult,
    val textSearch: DemoTextSearchSummary,
)

private class CliServiceDemoGenLoadingClient(
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

internal object NoOpDemoGenBackend : DemoGenBackend {
    override suspend fun bootstrap(workspaceRoot: Path): WorkspaceEnsureResult =
        error("NoOpDemoGenBackend.bootstrap must not be called (render-from-file path)")

    override suspend fun listAllSymbols(workspaceRoot: Path): List<Symbol> =
        error("NoOpDemoGenBackend.listAllSymbols must not be called (render-from-file path)")

    override suspend fun buildReportInstrumented(
        workspaceRoot: Path,
        symbol: Symbol,
        onStepComplete: (index: Int, durationMs: Long) -> Unit,
    ): DemoReport = error("NoOpDemoGenBackend.buildReportInstrumented must not be called (render-from-file path)")
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
