package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.Location
import java.nio.file.Path

/**
 * Helpers for rendering filesystem paths and `<file>:<line>` location strings
 * inside the demo views. Kept separate from any rendering concern so unit
 * tests don't have to instantiate Mordant terminals.
 */
internal object Paths {
    fun relative(workspaceRoot: Path, filePath: String): String {
        val absolute = Path.of(filePath).toAbsolutePath().normalize()
        val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
        return if (absolute.startsWith(normalizedRoot)) {
            normalizedRoot.relativize(absolute).toString()
        } else {
            absolute.toString()
        }
    }

    /** Bare file name (last path segment), e.g. `SymbolWalker.kt`. */
    fun fileName(filePath: String): String =
        Path.of(filePath).fileName?.toString() ?: filePath

    fun locationLine(workspaceRoot: Path, location: Location): String =
        "${relative(workspaceRoot, location.filePath)}:${location.startLine}"

    /** `<file>:<line>` where `<file>` is either the bare file name or the workspace-relative path. */
    fun locationLine(
        workspaceRoot: Path,
        location: Location,
        verbose: Boolean,
    ): String {
        val path = if (verbose) relative(workspaceRoot, location.filePath) else fileName(location.filePath)
        return "$path:${location.startLine}"
    }
}
