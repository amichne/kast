package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.RGB

/**
 * 256-color–aligned RGB palette for the demo transcript panel.
 *
 * Every value is chosen from the xterm-256 color cube so the rendering
 * degrades gracefully on terminals that lack truecolor support.  Kotter's
 * [com.varabyte.kotter.foundation.text.rgb] overloads accept [RGB] directly.
 */
internal object TranscriptPalette {
    /** Commands / user-initiated actions. Xterm-256 index 39 (#00AFFF). */
    val COMMAND: RGB = RGB(0, 175, 255)

    /** Confirmed / success outcomes. Xterm-256 index 78 (#5FD787). */
    val SUCCESS: RGB = RGB(95, 215, 135)

    /** Flagged / warnings. Xterm-256 index 221 (#FFD75F). */
    val WARNING: RGB = RGB(255, 215, 95)

    /** Errors / false positives / issues avoided. Xterm-256 index 203 (#FF5F5F). */
    val ERROR: RGB = RGB(255, 95, 95)

    /** Inline code snippets. Xterm-256 index 146 (#AFAFD7). */
    val CODE: RGB = RGB(175, 175, 215)

    /** File paths and location labels. Xterm-256 index 245 (#8A8A8A). */
    val PATH: RGB = RGB(138, 138, 138)

    /** Structural elements: borders, dividers. Xterm-256 index 240 (#585858). */
    val BORDER: RGB = RGB(88, 88, 88)

    /** Panel titles. Xterm-256 index 255 (#EEEEEE). */
    val TITLE: RGB = RGB(238, 238, 238)

    /** Maps a [KotterDemoStreamTone] to its palette color, or `null` for [KotterDemoStreamTone.DETAIL]. */
    fun toneColor(tone: KotterDemoStreamTone): RGB? = when (tone) {
        KotterDemoStreamTone.COMMAND -> COMMAND
        KotterDemoStreamTone.CONFIRMED -> SUCCESS
        KotterDemoStreamTone.FLAGGED -> WARNING
        KotterDemoStreamTone.ERROR -> ERROR
        KotterDemoStreamTone.STRUCTURE -> BORDER
        KotterDemoStreamTone.DETAIL -> null
    }
}
