package io.github.amichne.kast.demo

import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import com.varabyte.kotterx.decorations.BorderCharacters
import com.varabyte.kotterx.decorations.bordered

/**
 * Renders the bordered header box for an act.
 *
 * ```
 * ┌─────────────────────────────────────────────────────┐
 * │  Act 1 of 3 — Text Search                           │
 * │  grep -rn "execute" --include="*.kt"                │
 * └─────────────────────────────────────────────────────┘
 * ```
 */
fun RenderScope.renderActHeader(
    actNumber: Int,
    totalActs: Int,
    title: String,
    subtitle: String,
) {
    bordered(
        borderCharacters = BorderCharacters.BOX_THIN,
        paddingLeftRight = 1,
    ) {
        textLine("Act $actNumber of $totalActs \u2014 $title")
        text(subtitle)
    }
}
