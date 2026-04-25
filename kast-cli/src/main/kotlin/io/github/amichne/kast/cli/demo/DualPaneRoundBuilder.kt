package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.cli.DemoReport
import io.github.amichne.kast.cli.DemoTextMatch
import io.github.amichne.kast.cli.DemoTextMatchCategory
import io.github.amichne.kast.cli.DemoTextSearchSummary
import java.nio.file.Path

internal fun buildReferencesRound(
    report: DemoReport,
    textSearch: DemoTextSearchSummary,
): DualPaneRound {
    val references = report.references.references
    val scope = report.references.searchScope
    val rightLines = buildList {
        report.references.declaration?.let { symbol ->
            add(KotterDemoTranscriptLine("declaration ${symbol.kind.name.lowercase()} ${symbol.fqName}", KotterDemoStreamTone.COMMAND))
        }
        scope?.let {
            add(KotterDemoTranscriptLine("scope ${it.scope} exhaustive=${it.exhaustive}", KotterDemoStreamTone.CONFIRMED))
            add(KotterDemoTranscriptLine("searched ${it.searchedFileCount}/${it.candidateFileCount} candidate files", KotterDemoStreamTone.DETAIL))
        }
        references.forEach { reference ->
            add(
                KotterDemoTranscriptLine(
                    text = "${Paths.locationLine(report.workspaceRoot, reference, verbose = true)} reference",
                    tone = KotterDemoStreamTone.CONFIRMED,
                    codePreview = reference.preview.trim(),
                ),
            )
        }
    }
    return DualPaneRound(
        title = "References",
        leftCommand = """grep -rn "${report.resolvedSymbol.fqName.substringAfterLast('.')}" --include="*.kt"""",
        rightCommand = "kast references --symbol ${report.resolvedSymbol.fqName}",
        leftLines = textSearch.sampleMatches.map { it.toLeftLine(report.workspaceRoot) },
        rightLines = rightLines,
        leftFooter = "⚑ ${textSearch.totalMatches} hits · 0 type info · 0 scope · ${textSearch.categoryBreakdown()}",
        rightFooter = "✓ ${references.size} refs · typed · scoped · proven",
        scoreboard = listOf(
            ScoreboardRow(
                metric = "Noise reduction",
                grepValue = "${textSearch.totalMatches} hits",
                kastValue = "${references.size} refs",
                delta = "${noiseReductionPercent(textSearch.totalMatches, references.size)}% less",
                isNewCapability = false,
            ),
            ScoreboardRow(
                metric = "False positives",
                grepValue = textSearch.falsePositives.toString(),
                kastValue = "0",
                delta = "-${textSearch.falsePositives}",
                isNewCapability = false,
            ),
            ScoreboardRow(
                metric = "Type information",
                grepValue = "none",
                kastValue = "${report.resolvedSymbol.fqName} ${report.resolvedSymbol.kind}",
                delta = "NEW",
                isNewCapability = true,
            ),
            ScoreboardRow(
                metric = "Scope proof",
                grepValue = "none",
                kastValue = "exhaustive=${scope?.exhaustive ?: false}",
                delta = "NEW",
                isNewCapability = true,
            ),
        ),
    )
}

internal fun buildRenameRound(
    report: DemoReport,
    textSearch: DemoTextSearchSummary,
): DualPaneRound {
    val unsafeMatches = textSearch.sampleMatches.filter { it.category != DemoTextMatchCategory.LIKELY_CORRECT }
    val rightLines = report.rename.edits.map { edit -> edit.toRenameLine(report.workspaceRoot, report.resolvedSymbol.fqName.substringAfterLast('.')) } +
        report.rename.fileHashes.map { hash -> hash.toHashLine(report.workspaceRoot) }
    return DualPaneRound(
        title = "Rename",
        leftCommand = """sed -i '' "s/${report.resolvedSymbol.fqName.substringAfterLast('.')}/${report.resolvedSymbol.fqName.substringAfterLast('.')}Renamed/g"""",
        rightCommand = "kast rename --symbol ${report.resolvedSymbol.fqName} --new-name ${report.resolvedSymbol.fqName.substringAfterLast('.')}Renamed --dry-run",
        leftLines = unsafeMatches.map { match ->
            DualPaneLeftLine(
                text = "sed would rewrite ${Paths.relative(report.workspaceRoot, match.filePath)}:${match.lineNumber}",
                category = match.category,
                codePreview = match.preview,
            )
        },
        rightLines = rightLines,
        leftFooter = "⚑ ${unsafeMatches.size} blind edits, ${textSearch.falsePositives} would break",
        rightFooter = "✓ ${report.rename.edits.size} edits · ${report.rename.fileHashes.size} hash guards",
        scoreboard = listOf(
            ScoreboardRow(
                metric = "Files touched",
                grepValue = textSearch.filesTouched.toString(),
                kastValue = report.rename.affectedFiles.size.toString(),
                delta = "${textSearch.filesTouched - report.rename.affectedFiles.size}",
                isNewCapability = false,
            ),
            ScoreboardRow(
                metric = "Rename safety",
                grepValue = "blind sed",
                kastValue = "SHA-256 verified",
                delta = "NEW",
                isNewCapability = true,
            ),
        ),
    )
}

internal fun buildCallGraphRound(
    report: DemoReport,
    workspaceRoot: Path,
    textSearchOf: (String) -> DemoTextSearchSummary,
    verbose: Boolean,
): DualPaneRound {
    val callerNames = report.callHierarchy.root.children.map { it.simpleCallerName() }.distinct()
    val leftLines = callerNames.flatMap { callerName ->
        textSearchOf(callerName).sampleMatches.take(CALLER_GREP_SAMPLE_LIMIT).map { it.toLeftLine(workspaceRoot) }
    }
    val rightLines = renderCallTreePreview(
        workspaceRoot = workspaceRoot,
        root = report.callHierarchy.root,
        verbose = verbose,
        limit = CALL_GRAPH_RIGHT_LINE_LIMIT,
    ).map { line -> KotterDemoTranscriptLine(line, KotterDemoStreamTone.STRUCTURE) }

    return DualPaneRound(
        title = "Call Graph",
        leftCommand = callerNames.joinToString(prefix = "grep -rn ", separator = " && grep -rn ") { "\"$it\"" },
        rightCommand = "kast call-hierarchy --symbol ${report.resolvedSymbol.fqName} --direction incoming --depth 2",
        leftLines = leftLines,
        rightLines = rightLines,
        leftFooter = "⚑ caller identity unrecoverable: ${leftLines.size} hits across ${callerNames.size} names",
        rightFooter = "✓ ${report.callHierarchy.stats.totalNodes} nodes · depth ${report.callHierarchy.stats.maxDepthReached}",
        scoreboard = listOf(
            ScoreboardRow(
                metric = "Call graph",
                grepValue = "unavailable",
                kastValue = "bounded ${report.callHierarchy.stats.maxDepthReached}-hop tree",
                delta = "NEW",
                isNewCapability = true,
            ),
        ),
    )
}

internal fun buildDualPaneScenario(
    report: DemoReport,
    textSearchSummary: DemoTextSearchSummary,
    workspaceRoot: Path,
    verbose: Boolean,
    textSearchOf: (String) -> DemoTextSearchSummary,
): DualPaneScenario = DualPaneScenario(
    rounds = listOf(
        buildReferencesRound(report, textSearchSummary),
        buildRenameRound(report, textSearchSummary),
        buildCallGraphRound(report, workspaceRoot, textSearchOf, verbose),
    ),
)

private fun DemoTextMatch.toLeftLine(workspaceRoot: Path): DualPaneLeftLine =
    DualPaneLeftLine(
        text = "${Paths.relative(workspaceRoot, filePath)}:$lineNumber  $preview",
        category = category,
        codePreview = preview,
    )

private fun TextEdit.toRenameLine(workspaceRoot: Path, oldName: String): KotterDemoTranscriptLine =
    KotterDemoTranscriptLine(
        text = "${Paths.relative(workspaceRoot, filePath)}:${startOffset}..$endOffset  $oldName → $newText",
        tone = KotterDemoStreamTone.CONFIRMED,
    )

private fun FileHash.toHashLine(workspaceRoot: Path): KotterDemoTranscriptLine =
    KotterDemoTranscriptLine(
        text = "SHA-256 ${hash.take(HASH_PREVIEW_LENGTH)}…  ${Paths.relative(workspaceRoot, filePath)}",
        tone = KotterDemoStreamTone.CONFIRMED,
    )

private fun CallNode.simpleCallerName(): String =
    symbol.fqName.substringAfterLast('.')

private fun DemoTextSearchSummary.categoryBreakdown(): String =
    DemoTextMatchCategory.entries
        .filter { it != DemoTextMatchCategory.LIKELY_CORRECT }
        .mapNotNull { category -> categoryCounts[category]?.takeIf { it > 0 }?.let { "${category.name.lowercase()}=$it" } }
        .ifEmpty { listOf("clean") }
        .joinToString(" · ")

private fun noiseReductionPercent(grepHits: Int, kastHits: Int): Int {
    if (grepHits <= 0) return 0
    return (((grepHits - kastHits).coerceAtLeast(0).toDouble() / grepHits.toDouble()) * 100).toInt()
}

private const val HASH_PREVIEW_LENGTH: Int = 12
private const val CALLER_GREP_SAMPLE_LIMIT: Int = 6
private const val CALL_GRAPH_RIGHT_LINE_LIMIT: Int = 15
