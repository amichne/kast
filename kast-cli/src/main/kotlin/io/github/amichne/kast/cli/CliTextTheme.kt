package io.github.amichne.kast.cli

internal class CliTextTheme private constructor(
    private val ansiEnabled: Boolean,
) {
    fun title(text: String): String = style(text, "1;36")

    fun heading(text: String): String = style(text, "1;34")

    fun command(text: String): String = style(text, "1;32")

    fun option(text: String): String = style(text, "33")

    fun muted(text: String): String = style(text, "2")

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
