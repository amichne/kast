package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.CallHierarchyResult
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.RenameResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.cli.DemoReport
import io.github.amichne.kast.cli.DemoTextMatch
import io.github.amichne.kast.cli.DemoTextMatchCategory
import io.github.amichne.kast.cli.DemoTextSearchSummary
import java.nio.file.Path

/** Pure functions that turn analysis payloads into scene-builder calls. */
internal object DemoActs {
    const val REFERENCE_PREVIEW_LIMIT: Int = 8
    const val FILE_PREVIEW_LIMIT: Int = 6
    const val CALL_TREE_LIMIT: Int = 12
    const val SAMPLE_MATCH_LIMIT: Int = 12

    fun DemoScriptBuilder.openingBanner(workspaceRoot: Path) {
        banner("kast demo") {
            line("semantic analysis vs text search", emphasis = LineEmphasis.STRONG)
            blank()
            line("Workspace  $workspaceRoot")
        }
        blank()
    }

    fun DemoScriptBuilder.targetPanel(
        workspaceRoot: Path,
        symbol: Symbol,
    ) {
        val simpleName = symbol.fqName.substringAfterLast('.')
        val relFile = Paths.relative(workspaceRoot, symbol.location.filePath)
        banner("demo target") {
            line("Symbol  ${symbol.fqName}", emphasis = LineEmphasis.STRONG)
            line("Kind    ${symbol.kind}")
            line("File    $relFile")
            line("Offset  ${symbol.location.startOffset}")
            blank()
            line("> Find semantic references without comment, import, or substring noise")
            line("> Preview a safe rename before editing a single file")
            line("> Trace incoming callers and compare that graph with grep output")
            blank()
            line("Walker tip (Act 3): hop the $simpleName graph — r/c/o for references, callers, callees.", emphasis = LineEmphasis.DIM)
        }
        blank()
    }

    fun DemoScriptBuilder.act1TextSearchBaseline(
        workspaceRoot: Path,
        symbolName: String,
        summary: DemoTextSearchSummary,
    ) {
        section("Act 1 · text search baseline")
        step("grep '$symbolName' --include='*.kt'") {
            info()
            body {
                summary.sampleMatches.take(SAMPLE_MATCH_LIMIT).forEach { match ->
                    val rel = Paths.relative(workspaceRoot, match.filePath)
                    line(
                        text = "$rel:${match.lineNumber}  ${match.preview.take(90)}",
                        emphasis = match.emphasisForCategory(),
                        tag = match.bodyTag(),
                    )
                }
                if (summary.totalMatches > summary.sampleMatches.size) {
                    val remaining = summary.totalMatches - summary.sampleMatches.size
                    line("... and $remaining more matches", emphasis = LineEmphasis.DIM)
                }
                blank()
                line("grep found ${summary.totalMatches} matches for \"$symbolName\"", emphasis = LineEmphasis.STRONG)
                line("▸ ${summary.likelyCorrect} likely correct", emphasis = LineEmphasis.SUCCESS)
                if (summary.ambiguous > 0) {
                    line("▸ ${summary.ambiguous} ambiguous (imports)", emphasis = LineEmphasis.WARN)
                }
                if (summary.falsePositives > 0) {
                    val parts = summary.categoryCounts
                        .filterKeys { it != DemoTextMatchCategory.LIKELY_CORRECT }
                        .filterValues { it > 0 }
                        .entries
                        .joinToString(", ") { (kind, count) -> "$count ${kind.name.lowercase()}" }
                    line("▸ ${summary.falsePositives} likely false positives ($parts)", emphasis = LineEmphasis.ERROR)
                }
                blank()
                line(
                    text = "sed -i \"s/$symbolName/${symbolName}Renamed/g\" would touch ${summary.filesTouched} files — including ${summary.falsePositives} non-symbol matches",
                    emphasis = LineEmphasis.DIM,
                )
            }
        }
        blank()
    }

    fun DemoScriptBuilder.act2Semantic(
        workspaceRoot: Path,
        resolvedSymbol: Symbol,
        references: ReferencesResult,
        rename: RenameResult,
        callHierarchy: CallHierarchyResult,
    ) {
        section("Act 2 · semantic analysis")

        step("resolve") {
            success()
            body {
                line("fqName:     ${resolvedSymbol.fqName}", emphasis = LineEmphasis.STRONG)
                line("kind:       ${resolvedSymbol.kind}")
                resolvedSymbol.visibility?.let { line("visibility: $it") }
                line("location:   ${Paths.locationLine(workspaceRoot, resolvedSymbol.location)}")
                resolvedSymbol.containingDeclaration?.let { line("container:  $it") }
            }
        }
        blank()

        step("references") {
            success()
            body {
                line("references:  ${references.references.size}", emphasis = LineEmphasis.SUCCESS)
                references.searchScope?.let { scope ->
                    line("exhaustive:  ${scope.exhaustive}")
                    line("scope:       ${scope.scope}")
                    line("searched:    ${scope.searchedFileCount} / ${scope.candidateFileCount} files")
                }
                references.references.take(REFERENCE_PREVIEW_LIMIT).forEach { reference ->
                    line("  ${Paths.locationLine(workspaceRoot, reference)}  ${reference.preview.trim().take(80)}")
                }
                if (references.references.size > REFERENCE_PREVIEW_LIMIT) {
                    line("... and ${references.references.size - REFERENCE_PREVIEW_LIMIT} more", emphasis = LineEmphasis.DIM)
                }
            }
        }
        blank()

        val renameName = "${resolvedSymbol.fqName.substringAfterLast('.')}Renamed"
        step("rename --dry-run  (${resolvedSymbol.fqName.substringAfterLast('.')} → $renameName)") {
            success()
            body {
                line("edits:          ${rename.edits.size}", emphasis = LineEmphasis.SUCCESS)
                line("affected files: ${rename.affectedFiles.size}", emphasis = LineEmphasis.SUCCESS)
                line("file hashes:    ${rename.fileHashes.size} SHA-256 pre-images")
                rename.affectedFiles.take(FILE_PREVIEW_LIMIT).forEach { filePath ->
                    line("  ${Paths.relative(workspaceRoot, filePath)}")
                }
                if (rename.affectedFiles.size > FILE_PREVIEW_LIMIT) {
                    line("... and ${rename.affectedFiles.size - FILE_PREVIEW_LIMIT} more", emphasis = LineEmphasis.DIM)
                }
            }
        }
        blank()

        step("call-hierarchy (incoming, depth=2)") {
            success()
            body {
                line("incoming callers: ${callHierarchy.stats.totalNodes}", emphasis = LineEmphasis.SUCCESS)
                line("max depth:        ${callHierarchy.stats.maxDepthReached}")
                line("files visited:    ${callHierarchy.stats.filesVisited}")
                if (callHierarchy.stats.timeoutReached || callHierarchy.stats.maxTotalCallsReached) {
                    line("⚠ results truncated", emphasis = LineEmphasis.WARN)
                }
                renderCallTree(workspaceRoot, callHierarchy.root).forEach { rendered ->
                    line(rendered)
                }
            }
        }
        blank()
    }

    fun DemoScriptBuilder.comparisonSummary(report: DemoReport) {
        section("Side-by-side summary")
        comparisonTable {
            header("metric", "grep + sed", "kast")
            row(
                metric = "Matches found",
                left = "${report.textSearch.totalMatches} total / ${report.textSearch.likelyCorrect} likely true / ${report.textSearch.ambiguous} ambiguous",
                right = "${report.references.references.size} semantic references",
            )
            row("Symbol identity", "text only", "exact symbol identity")
            row("Kind awareness", "none", "knows the declaration kind")
            row("Call graph", "none", "${report.callHierarchy.stats.totalNodes} incoming callers")
            row(
                metric = "Rename plan",
                left = "blind sed across ${report.textSearch.filesTouched} files",
                right = "${report.rename.edits.size} edits across ${report.rename.affectedFiles.size} files",
            )
            row("Conflict detection", "none", "${report.rename.fileHashes.size} file hashes")
            val coverage = report.references.searchScope?.let {
                "exhaustive=${it.exhaustive} over ${it.searchedFileCount}/${it.candidateFileCount} files"
            } ?: "scope unavailable"
            row("Coverage signal", "none", coverage)
            row("Post-edit checks", "manual", "kast diagnostics")
            row("Node-by-node walk", "impossible — no identity", "r/c/o hops across the symbol graph")
        }
        blank()
    }

    fun DemoScriptBuilder.closingPanel() {
        banner("why the semantic pass wins") {
            line("grep only sees text, so it mixes real usages with imports, comments, string literals, and substring collisions.")
            line("kast resolves the exact declaration, returns true semantic references, previews a safe rename, and maps the incoming call graph before you edit anything.")
            line("Act 3 turns that into a live graph walk — references, callers, and callees addressed by symbol identity, not by substring.", emphasis = LineEmphasis.DIM)
            blank()
            line("Docs  https://amichne.github.io/kast/")
            line("Repo  https://github.com/amichne/kast")
        }
    }

    internal fun renderCallTree(workspaceRoot: Path, root: CallNode): List<String> {
        val lines = mutableListOf<String>()
        val remaining = intArrayOf(CALL_TREE_LIMIT)

        fun walk(node: CallNode, depth: Int) {
            if (remaining[0] <= 0) return
            remaining[0] -= 1
            val indent = "  ".repeat(depth.coerceAtLeast(0))
            val symbol = node.symbol
            val prefix = if (depth > 0) "├─ " else ""
            val location = Paths.locationLine(workspaceRoot, symbol.location)
            lines += "$indent$prefix${symbol.fqName.substringAfterLast('.')} (${symbol.kind})  $location"
            node.children.forEach { child -> walk(child, depth + 1) }
        }

        walk(root, depth = 0)
        return lines
    }

    private fun DemoTextMatch.emphasisForCategory(): LineEmphasis = when (category) {
        DemoTextMatchCategory.LIKELY_CORRECT -> LineEmphasis.SUCCESS
        DemoTextMatchCategory.IMPORT -> LineEmphasis.WARN
        DemoTextMatchCategory.COMMENT,
        DemoTextMatchCategory.STRING,
        DemoTextMatchCategory.SUBSTRING,
        -> LineEmphasis.ERROR
    }

    private fun DemoTextMatch.bodyTag(): BodyLineTag = when (category) {
        DemoTextMatchCategory.LIKELY_CORRECT -> BodyLineTag.CORRECT
        DemoTextMatchCategory.COMMENT -> BodyLineTag.COMMENT
        DemoTextMatchCategory.STRING -> BodyLineTag.STRING
        DemoTextMatchCategory.IMPORT -> BodyLineTag.IMPORT
        DemoTextMatchCategory.SUBSTRING -> BodyLineTag.SUBSTRING
    }
}

internal object Paths {
    fun relative(workspaceRoot: Path, filePath: String): String {
        val absolute = Path.of(filePath).toAbsolutePath().normalize()
        val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
        return if (absolute.startsWith(normalizedRoot)) {
            normalizedRoot.relativize(absolute).toString()
        } else {
            absolute.toString()
        }
    }

    fun locationLine(workspaceRoot: Path, location: Location): String =
        "${relative(workspaceRoot, location.filePath)}:${location.startLine}"
}
