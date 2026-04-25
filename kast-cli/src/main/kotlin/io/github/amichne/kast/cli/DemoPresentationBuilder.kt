package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.cli.demo.KotterDemoBranchSpec
import io.github.amichne.kast.cli.demo.KotterDemoOperationPresentation
import io.github.amichne.kast.cli.demo.KotterDemoOperationScenario
import io.github.amichne.kast.cli.demo.KotterDemoScenarioEvent
import io.github.amichne.kast.cli.demo.KotterDemoSessionPresentation
import io.github.amichne.kast.cli.demo.KotterDemoSessionScenario
import io.github.amichne.kast.cli.demo.KotterDemoStreamTone
import io.github.amichne.kast.cli.demo.KotterDemoTranscriptLine
import io.github.amichne.kast.cli.demo.Paths
import io.github.amichne.kast.cli.demo.renderCallTreePreview

internal class DemoPresentationBuilder {
    fun build(report: DemoReport, verbose: Boolean = true): KotterDemoSessionPresentation {
        val operations = listOf(
            referencesOperation(report, verbose),
            renameOperation(report, verbose),
            callersOperation(report, verbose),
        )
        return KotterDemoSessionPresentation(
            scenario = KotterDemoSessionScenario(
                initialOperationId = operations.first().id,
                operations = operations.map(PresentationOperationPlayback::toScenario),
            ),
            operations = operations.map(PresentationOperationPlayback::toPresentation),
        )
    }

    private fun referencesOperation(report: DemoReport, verbose: Boolean): PresentationOperationPlayback {
        val symbolName = report.resolvedSymbol.fqName
        val references = report.references.references
        return PresentationOperationPlayback(
            id = "references",
            label = "Find References",
            shortcutKey = 'f',
            query = "kast references --symbol $symbolName",
            phases = listOf(
                PresentationPhasePlayback(
                    id = "resolve",
                    lines = listOf(
                        transcriptLine("resolve ${report.resolvedSymbol.kind.name.lowercase()} $symbolName", KotterDemoStreamTone.COMMAND),
                        transcriptLine("declaration ${Paths.locationLine(report.workspaceRoot, report.resolvedSymbol.location, verbose)}", KotterDemoStreamTone.COMMAND),
                    ),
                ),
                PresentationPhasePlayback(
                    id = "search",
                    lines = buildList {
                        add(transcriptLine("semantic references ${references.size}", KotterDemoStreamTone.CONFIRMED))
                        add(transcriptLine("grep baseline ${report.textSearch.totalMatches} matches / ${report.textSearch.falsePositives} false positives", KotterDemoStreamTone.ERROR))
                        references.take(REFERENCE_PREVIEW_LIMIT).forEach { reference ->
                            add(
                                transcriptLine(
                                    Paths.locationLine(report.workspaceRoot, reference, verbose),
                                    tone = KotterDemoStreamTone.DETAIL,
                                    codePreview = reference.preview.trim().take(LIVE_LINE_PREVIEW_LIMIT),
                                ),
                            )
                        }
                        if (references.size > REFERENCE_PREVIEW_LIMIT) {
                            add(transcriptLine("... and ${references.size - REFERENCE_PREVIEW_LIMIT} more semantic hits", KotterDemoStreamTone.STRUCTURE))
                        }
                    },
                ),
                PresentationPhasePlayback(
                    id = "summarize",
                    lines = buildList {
                        report.references.searchScope?.let { scope ->
                            add(transcriptLine("scope ${scope.scope} exhaustive=${scope.exhaustive}", KotterDemoStreamTone.CONFIRMED))
                            add(transcriptLine("searched ${scope.searchedFileCount}/${scope.candidateFileCount} candidate files"))
                        } ?: add(transcriptLine("search scope unavailable", KotterDemoStreamTone.FLAGGED))
                        add(transcriptLine("declaration included ${report.references.declaration != null}", KotterDemoStreamTone.CONFIRMED))
                    },
                ),
            ),
        )
    }

    private fun renameOperation(report: DemoReport, verbose: Boolean): PresentationOperationPlayback {
        val symbolName = report.resolvedSymbol.fqName
        val renamed = "${report.resolvedSymbol.fqName.substringAfterLast('.')}Renamed"
        return PresentationOperationPlayback(
            id = "rename",
            label = "Rename Dry Run",
            shortcutKey = 'n',
            query = "kast rename --symbol $symbolName --new-name $renamed --dry-run",
            branches = renameBranches(report, verbose),
            phases = listOf(
                PresentationPhasePlayback(
                    id = "resolve",
                    lines = listOf(
                        transcriptLine("renaming ${report.resolvedSymbol.fqName.substringAfterLast(".")}", KotterDemoStreamTone.COMMAND),
                        transcriptLine("compare against grep touching ${report.textSearch.filesTouched} files blindly", KotterDemoStreamTone.ERROR),
                    ),
                ),
                PresentationPhasePlayback(
                    id = "plan",
                    lines = buildList {
                        add(transcriptLine("rename edits ${report.rename.edits.size}", KotterDemoStreamTone.CONFIRMED))
                        add(transcriptLine("affected files ${report.rename.affectedFiles.size}", KotterDemoStreamTone.CONFIRMED))
                        report.rename.affectedFiles.take(RENAME_FILE_PREVIEW_LIMIT).forEach { filePath ->
                            val displayPath = if (verbose) Paths.relative(report.workspaceRoot, filePath) else Paths.fileName(filePath)
                            add(transcriptLine(displayPath))
                        }
                        if (report.rename.affectedFiles.size > RENAME_FILE_PREVIEW_LIMIT) {
                            add(transcriptLine("... and ${report.rename.affectedFiles.size - RENAME_FILE_PREVIEW_LIMIT} more affected files", KotterDemoStreamTone.STRUCTURE))
                        }
                    },
                ),
                PresentationPhasePlayback(
                    id = "verify",
                    lines = listOf(
                        transcriptLine("preimage hashes ${report.rename.fileHashes.size}", KotterDemoStreamTone.CONFIRMED),
                        transcriptLine("semantic plan avoids ${report.textSearch.falsePositives} grep false positives", KotterDemoStreamTone.CONFIRMED),
                    ),
                ),
            ),
        )
    }

    private fun callersOperation(report: DemoReport, verbose: Boolean): PresentationOperationPlayback {
        val symbolName = report.resolvedSymbol.fqName
        val callTree = renderCallTreePreview(report.workspaceRoot, report.callHierarchy.root, verbose = verbose)
        return PresentationOperationPlayback(
            id = "callers",
            label = "Incoming Callers",
            shortcutKey = 'c',
            query = "kast call-hierarchy --symbol $symbolName --direction incoming --depth 2",
            phases = listOf(
                PresentationPhasePlayback(
                    id = "resolve",
                    lines = listOf(
                        transcriptLine("resolve incoming-call target $symbolName", KotterDemoStreamTone.COMMAND),
                        transcriptLine("grep cannot recover caller identity from substrings alone", KotterDemoStreamTone.ERROR),
                    ),
                ),
                PresentationPhasePlayback(
                    id = "walk",
                    lines = buildList {
                        add(transcriptLine("incoming callers ${report.callHierarchy.stats.totalNodes}", KotterDemoStreamTone.CONFIRMED))
                        callTree.take(CALL_TREE_PREVIEW_LIMIT).forEach { add(transcriptLine(it)) }
                        if (callTree.size > CALL_TREE_PREVIEW_LIMIT) {
                            add(transcriptLine("... and ${callTree.size - CALL_TREE_PREVIEW_LIMIT} more nodes", KotterDemoStreamTone.STRUCTURE))
                        }
                    },
                ),
                PresentationPhasePlayback(
                    id = "summarize",
                    lines = buildList {
                        add(transcriptLine("max depth ${report.callHierarchy.stats.maxDepthReached}"))
                        add(transcriptLine("files visited ${report.callHierarchy.stats.filesVisited}"))
                        if (report.callHierarchy.stats.timeoutReached || report.callHierarchy.stats.maxTotalCallsReached) {
                            add(transcriptLine("results truncated before the full graph completed", KotterDemoStreamTone.FLAGGED))
                        } else {
                            add(transcriptLine("graph completed without backend truncation", KotterDemoStreamTone.CONFIRMED))
                        }
                    },
                ),
            ),
        )
    }

    private fun renameBranches(report: DemoReport, verbose: Boolean): List<KotterDemoBranchSpec> {
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
                summary = if (verbose) Paths.relative(report.workspaceRoot, filePath) else Paths.fileName(filePath),
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

    private fun transcriptLine(
        text: String,
        tone: KotterDemoStreamTone = KotterDemoStreamTone.DETAIL,
        codePreview: String? = null,
    ): KotterDemoTranscriptLine = KotterDemoTranscriptLine(text, tone, codePreview)

    private data class PresentationOperationPlayback(
        val id: String,
        val label: String,
        val shortcutKey: Char,
        val query: String,
        val phases: List<PresentationPhasePlayback>,
        val branches: List<KotterDemoBranchSpec> = emptyList(),
    ) {
        fun toScenario(): KotterDemoOperationScenario {
            var currentAt = 0L
            val events = buildList {
                phases.forEach { phase ->
                    phase.lines.forEach { line ->
                        currentAt += SCENARIO_LINE_DELAY_MILLIS
                        add(KotterDemoScenarioEvent.Line(atMillis = currentAt, phaseId = phase.id, text = line.text, tone = line.tone, codePreview = line.codePreview))
                    }
                    currentAt += SCENARIO_PHASE_DELAY_MILLIS
                    add(KotterDemoScenarioEvent.Milestone(atMillis = currentAt, phaseId = phase.id))
                }
            }
            return KotterDemoOperationScenario(
                id = id,
                phases = phases.map(PresentationPhasePlayback::id),
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

    private data class PresentationPhasePlayback(
        val id: String,
        val lines: List<KotterDemoTranscriptLine>,
    )

    private companion object {
        const val CALL_TREE_PREVIEW_LIMIT = 8
        const val LIVE_LINE_PREVIEW_LIMIT = 72
        const val REFERENCE_PREVIEW_LIMIT = 5
        const val RENAME_FILE_PREVIEW_LIMIT = 6
        const val RENAME_BRANCH_COLUMN_LIMIT = 3
        const val SCENARIO_LINE_DELAY_MILLIS = 90L
        const val SCENARIO_PHASE_DELAY_MILLIS = 150L
    }
}
