package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.api.contract.SymbolKind

internal class CliTextTheme private constructor(
    private val ansiEnabled: Boolean,
) {
    fun title(text: String): String = style(text, "1;36")

    fun heading(text: String): String = style(text, "1;34")

    fun command(text: String): String = style(text, "1;32")

    fun option(text: String): String = style(text, "33")

    fun muted(text: String): String = style(text, "2")

    /** Light-grey text. Used for file-name headers and other secondary metadata. */
    fun fileHeader(text: String): String = style(text, "38;5;245")

    /** Color a symbol-kind label (e.g. "class", "function") consistently across the walker. */
    fun kind(kind: SymbolKind, text: String): String = style(text, kindCode(kind))

    /**
     * Return the raw SGR code for a [SymbolKind] so callers can inline the style.
     * Palette keeps each category visually distinct without fighting the panel chrome:
     * type declarations land on yellow / magenta, behavior on cyan, data on green.
     */
    fun kindCode(kind: SymbolKind): String = when (kind) {
        SymbolKind.CLASS, SymbolKind.OBJECT -> "1;33"
        SymbolKind.INTERFACE -> "1;35"
        SymbolKind.FUNCTION -> "1;36"
        SymbolKind.PROPERTY, SymbolKind.PARAMETER -> "1;32"
        SymbolKind.UNKNOWN -> "37"
    }

    private fun style(
        text: String,
        code: String,
    ): String {
        if (!ansiEnabled) {
            return text
        }
        return "\u001B[${code}m$text\u001B[0m"
    }

    companion object {
        fun detect(): CliTextTheme {
            val forced = System.getenv("CLICOLOR_FORCE") == "1" ||
                System.getProperty("kast.cli.forceColor") == "true"
            if (forced) {
                return ansi()
            }
            if (System.getenv("NO_COLOR") != null) {
                return plain()
            }
            val term = System.getenv("TERM")
            val interactiveTerminal = System.console() != null && !term.isNullOrBlank() && term != "dumb"
            return CliTextTheme(interactiveTerminal)
        }

        fun ansi(): CliTextTheme = CliTextTheme(true)

        private fun plain(): CliTextTheme = CliTextTheme(false)
    }
}
