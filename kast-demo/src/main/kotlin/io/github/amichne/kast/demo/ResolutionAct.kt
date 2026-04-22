package io.github.amichne.kast.demo

import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.color
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import com.varabyte.kotterx.grid.Cols
import com.varabyte.kotterx.grid.GridCharacters
import com.varabyte.kotterx.grid.grid
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders Act 2 — Symbol Resolution.
 */
fun RenderScope.renderResolutionAct(
    result: ResolutionResult,
    rippleEnabled: Boolean = false,
) {
    renderActHeader(
        actNumber = 2,
        totalActs = 3,
        title = "Symbol Resolution",
        subtitle = "kast resolve \"${result.fqn.substringAfterLast('.')}\" \u2192 ${result.fqn}",
    )
    textLine()

    // Declaration info
    text("  Declared in: ")
    cyan(isBright = true) { textLine("${result.declarationFile}:${result.declarationLine}") }
    text("  Type:        ")
    textLine(result.typeSignature)
    textLine()

    // Reference table
    if (result.refs.isNotEmpty()) {
        renderReferenceTable(result.refs)
        textLine()
    }

    // Delta summary
    textLine("  ${"─".repeat(60)}")
    text("  ${result.totalGrepHits} text matches  \u2192  ")
    green(isBright = true) {
        text("${result.refs.size} actual references to ${result.fqn}")
    }
    textLine()

    val noisePercent = if (result.totalGrepHits > 0) {
        (max(result.totalGrepHits - result.refs.size, 0).toDouble() / result.totalGrepHits * 100)
            .roundToInt()
            .coerceIn(0, 100)
    } else {
        0
    }
    text("  Noise eliminated: ")
    green(isBright = true) { textLine("$noisePercent%") }
    textLine("  ${"─".repeat(60)}")

    if (rippleEnabled) {
        textLine()
        black(isBright = true) { textLine("  [Enter] \u2192 explore caller graph") }
    }
}

private fun RenderScope.renderReferenceTable(refs: List<ResolvedReference>) {
    grid(
        Cols { fit(minWidth = 20); fit(minWidth = 4); fit(minWidth = 4); fit(minWidth = 12); fit(minWidth = 8) },
        characters = GridCharacters.BOX_THIN,
        paddingLeftRight = 1,
    ) {
        // Header row
        cell { text("File") }
        cell { text("Line") }
        cell { text("Kind") }
        cell { text("Resolved Type") }
        cell { text("Module") }

        // Data rows
        for (ref in refs) {
            cell { text(ref.file) }
            cell { text(ref.line.toString()) }
            cell { text(ref.kind.name.lowercase()) }
            cell { text(ref.resolvedType) }
            cell {
                color(ModulePalette.colorFor(ref.module))
                text(ref.module)
            }
        }
    }
}
