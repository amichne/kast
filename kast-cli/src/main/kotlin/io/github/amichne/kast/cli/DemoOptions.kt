package io.github.amichne.kast.cli

import java.nio.file.Path

internal data class DemoOptions(
    val workspaceRoot: Path,
    val symbolFilter: String?,
    val walkMode: DemoWalkMode = DemoWalkMode.AUTO,
)

/**
 * Whether the interactive symbol-graph walker (Act 3) should run.
 *
 * - [AUTO] runs the walker only when stdin is a TTY and `--symbol` was not
 *   supplied, matching the "take me through it" default for a terminal demo.
 * - [ENABLED] forces the walker to run even without a TTY — useful when
 *   driving the CLI over a scripted transport.
 * - [DISABLED] skips the walker entirely, restoring the non-interactive
 *   rendered report used by CI and older call sites.
 */
internal enum class DemoWalkMode {
    AUTO,
    ENABLED,
    DISABLED,
}
