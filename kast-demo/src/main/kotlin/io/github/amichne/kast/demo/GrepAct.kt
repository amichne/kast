package io.github.amichne.kast.demo

import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import com.varabyte.kotterx.grid.Cols
import com.varabyte.kotterx.grid.GridCharacters
import com.varabyte.kotterx.grid.grid

/**
 * Renders Act 1 — Text Search (grep result visualization).
 *
 * Layout:
 * - Bordered header with act info
 * - Category table (Category | Count | Example)
 * - Summary line in bright red
 */
fun RenderScope.renderGrepAct(result: GrepResult) {
    renderActHeader(
        actNumber = 1,
        totalActs = 3,
        title = "Text Search",
        subtitle = result.command,
    )
    textLine()

    if (result.categories.isNotEmpty()) {
        renderGrepCategoryTable(result.categories)
        textLine()
    }

    red(isBright = true) {
        textLine("${result.totalHits} grep hits. No type information. No scope. Just noise.")
    }
}

private fun RenderScope.renderGrepCategoryTable(categories: List<GrepCategory>) {
    grid(
        Cols { fit(minWidth = 16); fit(minWidth = 5); fit(minWidth = 20) },
        characters = GridCharacters.BOX_THIN,
        paddingLeftRight = 1,
    ) {
        // Header row
        cell { text("Category") }
        cell { text("Count") }
        cell { text("Example") }

        // Data rows
        for (cat in categories) {
            cell { text(cat.name) }
            cell { text(cat.count.toString()) }
            cell { text(cat.example ?: "") }
        }
    }
}
