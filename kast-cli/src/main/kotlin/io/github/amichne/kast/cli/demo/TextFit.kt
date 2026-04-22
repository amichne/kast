package io.github.amichne.kast.cli.demo

/**
 * Tiny pure-string layout helpers used by the walker rows.
 * Mordant handles panel/table layout; this module only deals with
 * single-line truncation and balanced two-column splits.
 */
internal object TextFit {
    fun truncate(text: String, width: Int): String = when {
        width <= 0 -> ""
        text.length <= width -> text
        width == 1 -> "…"
        else -> text.take(width - 1) + "…"
    }

    /** Right-biased truncation: keep the tail (e.g. file name) visible. */
    fun truncateLeft(text: String, width: Int): String = when {
        width <= 0 -> ""
        text.length <= width -> text
        width == 1 -> "…"
        else -> "…" + text.takeLast(width - 1)
    }
}
