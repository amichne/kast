package io.github.amichne.kast.cli

internal enum class DemoGenOutputFormat { TERMINAL, MARKDOWN, JSON }

internal data class DemoGenOptions(
    val repoUrl: String,
    val symbolCount: Int = 3,
    val output: DemoGenOutputFormat = DemoGenOutputFormat.TERMINAL,
    /** Backend selection (always standalone for cloned repos, but kept for parity with DemoOptions). */
    val backend: String? = "standalone",
    val verbose: Boolean = false,
)
