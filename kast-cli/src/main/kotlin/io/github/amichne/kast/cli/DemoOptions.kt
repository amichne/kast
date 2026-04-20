package io.github.amichne.kast.cli

import java.nio.file.Path

internal data class DemoOptions(
    val workspaceRoot: Path,
    val symbolFilter: String?,
    val walkMode: DemoWalkMode = DemoWalkMode.AUTO,
    /**
     * Backend the demo should talk to.
     *  - `null` — auto-select: prefer a live IntelliJ plugin backend when one
     *    is discoverable, otherwise fall back to (and auto-start if needed)
     *    the standalone JVM daemon.
     *  - `"standalone"` — force the standalone JVM daemon.
     *  - `"intellij"` — require a running IntelliJ IDEA instance with the
     *    Kast plugin; fails fast when no IntelliJ runtime is available.
     */
    val backend: String? = null,
    /**
     * When `true`, the demo renders fully-qualified names and workspace-
     * relative paths everywhere (symbol identity, walker tree, declaration
     * headers). When `false` (default), simple names and bare file names are
     * used, keeping tree rows readable in a standard terminal width.
     */
    val verbose: Boolean = false,
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
