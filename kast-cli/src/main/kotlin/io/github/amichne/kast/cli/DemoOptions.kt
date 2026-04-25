package io.github.amichne.kast.cli

import java.nio.file.Path

internal data class DemoOptions(
    val workspaceRoot: Path,
    val symbolFilter: String?,
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
    val fixture: Path? = null,
    /**
     * When `true`, the demo renders fully-qualified names and workspace-
     * relative paths everywhere (symbol identity, semantic panels, declaration
     * headers). When `false` (default), simple names and bare file names are
     * used, keeping tree rows readable in a standard terminal width.
     */
    val verbose: Boolean = false,
)
