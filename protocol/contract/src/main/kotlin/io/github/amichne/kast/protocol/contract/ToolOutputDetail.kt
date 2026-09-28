package io.github.amichne.kast.protocol.contract

/** Presentation only: semantic execution and retained evidence never depend on this choice. */
enum class ToolOutputDetail {
    COMPACT,
    VERBOSE;

    companion object {
        fun fromVerbose(verbose: Boolean): ToolOutputDetail = if (verbose) VERBOSE else COMPACT
    }
}
