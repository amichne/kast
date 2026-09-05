package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal enum class ObserverDirectoryFailure { INVALID_PATH }

/** Lexical display scope: history rendering never observes the current filesystem. */
internal class ObserverWorkingDirectory private constructor(val path: Path) {
    companion object {
        internal fun from(directory: CanonicalBrokerDirectory): ObserverWorkingDirectory =
            ObserverWorkingDirectory(directory.path)

        /**
         * Refines a historical cwd into an absolute normalized display scope without filesystem
         * observation. Invalid syntax is finite failure; extraction is confined to link rendering.
         */
        internal fun admit(raw: String): Refinement<ObserverWorkingDirectory, ObserverDirectoryFailure> {
            if (raw.isBlank() || raw.length > 4_096 || raw.any(Char::isISOControl)) {
                return Refinement.Rejected(ObserverDirectoryFailure.INVALID_PATH)
            }
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return Refinement.Rejected(ObserverDirectoryFailure.INVALID_PATH)
            }
            return if (path.isAbsolute && path.normalize() == path) {
                Refinement.Refined(ObserverWorkingDirectory(path))
            } else {
                Refinement.Rejected(ObserverDirectoryFailure.INVALID_PATH)
            }
        }
    }
}
