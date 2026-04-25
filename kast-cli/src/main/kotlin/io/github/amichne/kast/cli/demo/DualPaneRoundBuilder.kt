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
            add(
                KotterDemoTranscriptLine(
                    "declaration ${symbol.kind.name.lowercase()} ${symbol.fqName.substringAfterLast('.')}",
                    KotterDemoStreamTone.COMMAND,
                ),
            )
        }
        scope?.let {
            add(KotterDemoTranscriptLine("scope ${it.scope} · ${it.searchedFileCount}/${it.candidateFileCount} files · exhaustive=${it.exhaustive}", KotterDemoStreamTone.CONFIRMED))
        }
        references.take(REFERENCE_RIGHT_PREVIEW_LIMIT).forEach { reference ->
            add(
                KotterDemoTranscriptLine(
                    text = "ref · ${simpleLocation(reference)} · ${previewSnippet(reference.preview)}",
                    tone = KotterDemoStreamTone.CONFIRMED,
                ),
            )
        }
        val omitted = references.size - REFERENCE_RIGHT_PREVIEW_LIMIT
        if (omitted > 0) {
            add(KotterDemoTranscriptLine("… $omitted more refs in result", KotterDemoStreamTone.STRUCTURE))
        }
    }
    val symbolName = report.resolvedSymbol.fqName.substringAfterLast('.')
    return DualPaneRound(
        title = "References",
        leftCommand = """grep "$symbolName"""",
        rightCommand = "kast refs $symbolName",
        leftLines = textSearch.sampleMatches.take(LEFT_PREVIEW_LIMIT).map(DemoTextMatch::toLeftLine),
        rightLines = rightLines,
        leftFooter = "⚑ ${textSearch.totalMatches} hits · ${textSearch.falsePositives} noisy · ${textSearch.categoryBreakdown()}",
        rightFooter = "✓ ${references.size} refs · ${report.resolvedSymbol.kind} · scoped",
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
                kastValue = "${report.resolvedSymbol.kind} $symbolName",
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
    val symbolName = report.resolvedSymbol.fqName.substringAfterLast('.')
    val newName = "${symbolName}Renamed"
    val rightLines = buildList {
        report.rename.edits.take(RENAME_EDIT_PREVIEW_LIMIT).forEach { edit -> add(edit.toRenameLine(symbolName)) }
        val omittedEdits = report.rename.edits.size - RENAME_EDIT_PREVIEW_LIMIT
        if (omittedEdits > 0) {
            add(KotterDemoTranscriptLine("… $omittedEdits more edits in plan", KotterDemoStreamTone.STRUCTURE))
        }
        report.rename.fileHashes.take(RENAME_HASH_PREVIEW_LIMIT).forEach { hash -> add(hash.toHashLine()) }
        val omittedHashes = report.rename.fileHashes.size - RENAME_HASH_PREVIEW_LIMIT
        if (omittedHashes > 0) {
            add(KotterDemoTranscriptLine("… $omittedHashes more hash guards", KotterDemoStreamTone.STRUCTURE))
        }
    }
    return DualPaneRound(
        title = "Rename",
        leftCommand = """sed "$symbolName" → "$newName"""",
        rightCommand = "kast rename $symbolName → $newName",
        leftLines = unsafeMatches.take(LEFT_PREVIEW_LIMIT).map { match ->
            DualPaneLeftLine(
                text = "${simpleLocation(match.filePath, match.lineNumber)} · ${match.category.label()} · would edit",
                category = match.category,
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
        textSearchOf(callerName).sampleMatches.take(CALLER_GREP_SAMPLE_LIMIT).map(DemoTextMatch::toLeftLine)
    }
    val rightLines = renderCallTreePreview(
        workspaceRoot = workspaceRoot,
        root = report.callHierarchy.root,
        verbose = false,
        limit = CALL_GRAPH_RIGHT_PREVIEW_LIMIT,
    ).map { line -> KotterDemoTranscriptLine(line, KotterDemoStreamTone.STRUCTURE) }
    val symbolName = report.resolvedSymbol.fqName.substringAfterLast('.')

    return DualPaneRound(
        title = "Call Graph",
        leftCommand = callerNames.joinToString(prefix = "grep callers: ", separator = ", "),
        rightCommand = "kast callers $symbolName depth=2",
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

private fun DemoTextMatch.toLeftLine(): DualPaneLeftLine =
    DualPaneLeftLine(
        text = "${simpleLocation(filePath, lineNumber)} · ${category.label()} · ${previewSnippet(preview)}",
        category = category,
    )

private fun TextEdit.toRenameLine(oldName: String): KotterDemoTranscriptLine =
    KotterDemoTranscriptLine(
        text = "edit · ${Paths.fileName(filePath)} · $oldName → $newText",
        tone = KotterDemoStreamTone.CONFIRMED,
    )

private fun FileHash.toHashLine(): KotterDemoTranscriptLine =
    KotterDemoTranscriptLine(
        text = "hash · ${Paths.fileName(filePath)} · ${hash.take(HASH_PREVIEW_LENGTH)}…",
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

private fun DemoTextMatchCategory.label(): String = when (this) {
    DemoTextMatchCategory.LIKELY_CORRECT -> "candidate"
    DemoTextMatchCategory.COMMENT -> "comment"
    DemoTextMatchCategory.STRING -> "string"
    DemoTextMatchCategory.IMPORT -> "import"
    DemoTextMatchCategory.SUBSTRING -> "substring"
}

private fun simpleLocation(location: Location): String =
    simpleLocation(location.filePath, location.startLine)

private fun simpleLocation(filePath: String, lineNumber: Int): String =
    "${Paths.fileName(filePath)}:$lineNumber"

private fun previewSnippet(preview: String): String =
    TextFit.truncate(
        preview.trim()
            .replace(Regex("^//\\s*|^/\\*\\s*|^\\*\\s*"), "")
            .replace(Regex("\\s+"), " "),
        PREVIEW_SNIPPET_LIMIT,
    )

private fun noiseReductionPercent(grepHits: Int, kastHits: Int): Int {
    if (grepHits <= 0) return 0
    return (((grepHits - kastHits).coerceAtLeast(0).toDouble() / grepHits.toDouble()) * 100).toInt()
}

private const val HASH_PREVIEW_LENGTH: Int = 12
private const val LEFT_PREVIEW_LIMIT: Int = 9
private const val REFERENCE_RIGHT_PREVIEW_LIMIT: Int = 6
private const val RENAME_EDIT_PREVIEW_LIMIT: Int = 5
private const val RENAME_HASH_PREVIEW_LIMIT: Int = 2
private const val CALLER_GREP_SAMPLE_LIMIT: Int = 2
private const val CALL_GRAPH_RIGHT_PREVIEW_LIMIT: Int = 8
private const val PREVIEW_SNIPPET_LIMIT: Int = 44
