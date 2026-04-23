package io.github.amichne.kast.cli

import java.nio.file.Path

internal enum class DemoGenOutputFormat { TERMINAL, MARKDOWN, JSON }

internal data class DemoGenOptions(
    val repoUrl: String? = null,
    val symbolCount: Int = 3,
    val output: DemoGenOutputFormat = DemoGenOutputFormat.TERMINAL,
    /** Backend selection (always standalone for cloned repos, but kept for parity with DemoOptions). */
    val backend: String? = "standalone",
    val verbose: Boolean = false,
    val local: Boolean = false,
    val background: Boolean = false,
    val workspaceRoot: Path? = null,
)

internal data class DemoRenderOptions(
    val jsonFile: Path,
    val verbose: Boolean = false,
)
