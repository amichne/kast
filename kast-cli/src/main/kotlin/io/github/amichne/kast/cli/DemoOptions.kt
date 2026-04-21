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
    /**
     * Minimum number of resolved references a candidate must have to qualify
     * for auto-selection. Mirrors `--min-refs` from `kast-demo-spec.md`.
     * Ignored when [symbolFilter] is supplied.
     */
    val minRefs: Int = 5,
    /**
     * Minimum grep-hits / resolved-refs ratio a candidate must have to
     * qualify for auto-selection. Mirrors `--noise-ratio`. Ignored when
     * [symbolFilter] is supplied.
     */
    val noiseRatio: Double = 2.0,
    /**
     * Maximum depth used when computing the incoming call hierarchy that
     * powers Act 2's caller tree. Mirrors `--depth`. The shape of the tree
     * is independent of the interactive walker (Act 3), which is governed
     * by [walkMode].
     */
    val rippleDepth: Int = 2,
) {
    init {
        require(minRefs >= 0) { "minRefs must be >= 0 (was $minRefs)" }
        require(noiseRatio >= 0.0) { "noiseRatio must be >= 0 (was $noiseRatio)" }
        require(rippleDepth in 1..MAX_RIPPLE_DEPTH) {
            "rippleDepth must be in 1..$MAX_RIPPLE_DEPTH (was $rippleDepth)"
        }
    }

    internal companion object {
        const val MAX_RIPPLE_DEPTH: Int = 8
    }
}

/**
 * Whether the interactive symbol-graph walker should run after the transcript.
 *
 * - [AUTO] preserves the three-act transcript and does not enter the walker
 *   unless the CLI later grows an explicit prompt/acceptance step.
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
