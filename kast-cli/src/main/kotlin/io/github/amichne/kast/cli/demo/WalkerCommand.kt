package io.github.amichne.kast.cli.demo

/** Parsed user input for the interactive symbol walker. */
internal sealed interface WalkerCommand {
    data object Help : WalkerCommand
    data object Back : WalkerCommand
    data object Quit : WalkerCommand
    data object ShowDeclaration : WalkerCommand

    /** Hop to the n-th reference (1-based as rendered to the user). */
    data class JumpReference(val oneBasedIndex: Int) : WalkerCommand

    /** Hop to the n-th incoming caller. */
    data class JumpCaller(val oneBasedIndex: Int) : WalkerCommand

    /** Hop to the n-th outgoing callee. */
    data class JumpCallee(val oneBasedIndex: Int) : WalkerCommand

    /** Run grep for the current symbol's simple name and print a few lines. */
    data class GrepComparison(val maxLines: Int = 6) : WalkerCommand

    /** Arbitrary text that does not parse; renderer shows a usage hint. */
    data class Unknown(val raw: String) : WalkerCommand

    /** End-of-stream (stdin closed). Treated like quit. */
    data object EndOfInput : WalkerCommand

    companion object {
        fun parse(raw: String?): WalkerCommand {
            if (raw == null) return EndOfInput
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return Help
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            val head = parts[0].lowercase()
            val arg = parts.getOrNull(1)?.trim().orEmpty()
            return when (head) {
                "q", "quit", "exit" -> Quit
                "b", "back" -> Back
                "h", "help", "?" -> Help
                "s", "show" -> ShowDeclaration
                "g", "grep" -> GrepComparison(maxLines = arg.toIntOrNull() ?: 6)
                "r", "ref", "references" -> arg.toIntOrNull()?.let { JumpReference(it) } ?: Unknown(trimmed)
                "c", "caller", "callers" -> arg.toIntOrNull()?.let { JumpCaller(it) } ?: Unknown(trimmed)
                "o", "out", "callee", "callees" -> arg.toIntOrNull()?.let { JumpCallee(it) } ?: Unknown(trimmed)
                else -> Unknown(trimmed)
            }
        }
    }
}
