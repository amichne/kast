package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.demo.ConversationLine
import io.github.amichne.kast.demo.ConversationTone
import io.github.amichne.kast.demo.ConversationTurn
import io.github.amichne.kast.demo.DualPaneConversation
import java.nio.file.Path
import java.nio.file.Paths as JPaths

/**
 * Translates a [DemoReport] into a [DualPaneConversation] that contrasts a
 * naive grep-only "baseline" assistant on the left with the kast-augmented
 * assistant on the right. Three turns are produced per symbol: find usages,
 * rename, and find callers.
 */
internal object ConversationTemplateEngine {

    private const val MAX_LINE = 80
    private const val SAMPLE_REFERENCES = 5
    private const val SAMPLE_EDITS = 5
    private const val SAMPLE_CALLERS = 8

    fun build(report: DemoReport): DualPaneConversation {
        val fqName = report.resolvedSymbol.fqName
        val simpleName = fqName.substringAfterLast('.')
        val workspaceRoot = report.workspaceRoot
        val turns = listOf(
            buildFindUsagesTurn(report, simpleName, fqName, workspaceRoot),
            buildRenameTurn(report, simpleName, workspaceRoot),
            buildCallersTurn(report, simpleName, workspaceRoot),
        )
        return DualPaneConversation(
            symbolFqn = fqName,
            simpleName = simpleName,
            turns = turns,
        )
    }

    private fun buildFindUsagesTurn(
        report: DemoReport,
        simpleName: String,
        fqName: String,
        workspaceRoot: Path,
    ): ConversationTurn {
        val text = report.textSearch
        val left = buildList {
            add(
                line(
                    "I ran `grep -r ${simpleName}` and found ${text.totalMatches} usages " +
                        "across ${text.filesTouched} files.",
                    ConversationTone.NORMAL,
                ),
            )
            add(line("${text.likelyCorrect} look like real call sites.", ConversationTone.NORMAL))
            add(
                line(
                    "${text.ambiguous} are ambiguous matches in unrelated files.",
                    ConversationTone.WARNING,
                ),
            )
            add(
                line(
                    "${text.falsePositives} are clearly false positives in comments/strings.",
                    ConversationTone.ERROR,
                ),
            )
            add(line("Best guess: there are about ${text.totalMatches} usages.", ConversationTone.NORMAL))
        }

        val references = report.references.references
        val right = buildList {
            if (references.isEmpty()) {
                add(line("(no result)", ConversationTone.DIM))
            } else {
                add(line("Resolved symbol: $fqName", ConversationTone.SUCCESS))
                add(line("${references.size} precise references:", ConversationTone.SUCCESS))
                references.take(SAMPLE_REFERENCES).forEach { ref ->
                    add(line(formatReference(workspaceRoot, ref)))
                }
                if (references.size > SAMPLE_REFERENCES) {
                    add(
                        line(
                            "... and ${references.size - SAMPLE_REFERENCES} more",
                            ConversationTone.DIM,
                        ),
                    )
                }
            }
        }

        return ConversationTurn(
            userPrompt = "Find usages of `$simpleName`",
            leftResponse = left,
            rightResponse = right,
        )
    }

    private fun buildRenameTurn(
        report: DemoReport,
        simpleName: String,
        workspaceRoot: Path,
    ): ConversationTurn {
        val text = report.textSearch
        val newName = "${simpleName}V2"
        val totalNoise = text.ambiguous + text.falsePositives
        val left = listOf(
            line(
                "I'll run `sed -i 's/${simpleName}/$newName/g' **/*.kt` across ${text.filesTouched} files.",
                ConversationTone.NORMAL,
            ),
            line(
                "This will also rewrite ${text.ambiguous} ambiguous matches and " +
                    "${text.falsePositives} string/comment hits.",
                ConversationTone.WARNING,
            ),
            line(
                "Manual review required for ~$totalNoise unsafe edits.",
                ConversationTone.ERROR,
            ),
        )

        val edits = report.rename.edits
        val hashGuards = report.rename.fileHashes.isNotEmpty()
        val right = buildList {
            if (edits.isEmpty()) {
                add(line("(no result)", ConversationTone.DIM))
            } else {
                add(line("${edits.size} precise edits planned:", ConversationTone.SUCCESS))
                edits.take(SAMPLE_EDITS).forEach { edit ->
                    add(line(formatEdit(workspaceRoot, edit, simpleName)))
                }
                if (edits.size > SAMPLE_EDITS) {
                    add(line("... and ${edits.size - SAMPLE_EDITS} more edits", ConversationTone.DIM))
                }
                if (hashGuards) {
                    add(line("Each edit guarded by content hash.", ConversationTone.DIM))
                }
            }
        }

        return ConversationTurn(
            userPrompt = "Rename `$simpleName` to `$newName`",
            leftResponse = left,
            rightResponse = right,
        )
    }

    private fun buildCallersTurn(
        report: DemoReport,
        simpleName: String,
        workspaceRoot: Path,
    ): ConversationTurn {
        val text = report.textSearch
        val left = listOf(
            line(
                "I cannot determine callers from text alone — grep finds occurrences " +
                    "but not call sites.",
                ConversationTone.ERROR,
            ),
            line(
                "The closest I can do is filter the ${text.totalMatches} matches to lines " +
                    "containing `(`, but that's unreliable.",
                ConversationTone.WARNING,
            ),
        )

        val callers = flattenCallers(report.callHierarchy.root)
        val right = buildList {
            if (callers.isEmpty()) {
                add(line("(no result)", ConversationTone.DIM))
            } else {
                add(line("${callers.size} incoming caller(s):", ConversationTone.SUCCESS))
                callers.take(SAMPLE_CALLERS).forEach { node ->
                    val location = node.callSite ?: node.symbol.location
                    val label = "${node.symbol.fqName} (${formatLocationShort(workspaceRoot, location)})"
                    add(line(label))
                }
                if (callers.size > SAMPLE_CALLERS) {
                    add(line("... and ${callers.size - SAMPLE_CALLERS} more", ConversationTone.DIM))
                }
            }
        }

        return ConversationTurn(
            userPrompt = "Who calls `$simpleName`?",
            leftResponse = left,
            rightResponse = right,
        )
    }

    private fun line(text: String, tone: ConversationTone = ConversationTone.NORMAL): ConversationLine =
        ConversationLine(truncate(text), tone)

    private fun truncate(text: String): String {
        val collapsed = text.replace('\n', ' ').replace('\r', ' ')
        return if (collapsed.length <= MAX_LINE) collapsed else collapsed.take(MAX_LINE - 1) + "…"
    }

    private fun formatReference(workspaceRoot: Path, ref: Location): String {
        val rel = relativize(workspaceRoot, ref.filePath)
        val preview = ref.preview.trim()
        return "$rel:${ref.startLine}  $preview"
    }

    private fun formatEdit(workspaceRoot: Path, edit: TextEdit, oldName: String): String {
        val rel = relativize(workspaceRoot, edit.filePath)
        return "$rel  `$oldName` → `${edit.newText}`"
    }

    private fun formatLocationShort(workspaceRoot: Path, location: Location): String =
        "${relativize(workspaceRoot, location.filePath)}:${location.startLine}"

    private fun relativize(workspaceRoot: Path, filePath: String): String =
        runCatching {
            workspaceRoot.relativize(JPaths.get(filePath)).toString()
        }.getOrElse { filePath }

    private fun flattenCallers(root: CallNode): List<CallNode> {
        val out = mutableListOf<CallNode>()
        fun walk(node: CallNode) {
            node.children.forEach { child ->
                out.add(child)
                walk(child)
            }
        }
        walk(root)
        return out
    }
}
