package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Only explicit absolute directories enter the child; the admitted bundled JBR always wins. */
internal class ManagedHeadlessExecutablePath private constructor(val value: String) {
    companion object {
        private const val MAXIMUM_PATH_CHARACTERS = 65_536

        fun parse(
            javaHome: Path,
            raw: String?,
        ): Refinement<ManagedHeadlessExecutablePath, ManagedHeadlessLaunchFailure> {
            val selected = raw ?: "/usr/bin:/bin"
            if (selected.length > MAXIMUM_PATH_CHARACTERS) return rejected()
            val directories = selected.split(':')
            try {
                for (directory in directories) {
                    if (directory.isEmpty() || directory.any(Char::isISOControl)) return rejected()
                    val path = Path.of(directory)
                    if (!path.isAbsolute || path.normalize() != path) return rejected()
                }
            } catch (_: InvalidPathException) {
                return rejected()
            }
            return Refinement.Refined(
                ManagedHeadlessExecutablePath((listOf("$javaHome/bin") + directories).distinct().joinToString(":"))
            )
        }

        private fun rejected() = Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_EXECUTABLE_PATH)
    }
}
