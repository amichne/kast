package io.github.amichne.kast.appserver.provider

import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Admitted observer-local paths and Markdown escaping for preview links. */
@JvmInline
internal value class ObserverFilePath private constructor(val value: String) {
    fun link(): String = "[${value.substringAfterLast('/').markdownLabel()}](<${value.markdownDestination()}>)"

    companion object {
        fun admit(raw: String, observerDirectory: Path): ObserverFilePath? {
            if (raw.isBlank()) return null
            if (raw.length > MAXIMUM_FILE_LENGTH) return null
            if (raw.any(::isForbiddenPathCharacter)) return null
            return try {
                val candidate = Path.of(raw).normalize()
                val relative =
                    when {
                        !candidate.isAbsolute -> candidate
                        candidate.startsWith(observerDirectory) -> observerDirectory.relativize(candidate)
                        else -> return null
                    }
                admitRelative(relative)
            } catch (_: InvalidPathException) {
                null
            }
        }

        fun admitSource(
            raw: String,
            canonicalRoot: String,
            observerDirectory: Path,
        ): ObserverFilePath? {
            if (raw.isBlank() || canonicalRoot.isBlank()) return null
            if (raw.length > MAXIMUM_FILE_LENGTH || canonicalRoot.length > MAXIMUM_FILE_LENGTH) return null
            if (raw.any(::isForbiddenPathCharacter) || canonicalRoot.any(::isForbiddenPathCharacter)) return null
            return try {
                val root = Path.of(canonicalRoot).normalize()
                if (!root.isAbsolute) return null
                val candidate = Path.of(raw).normalize()
                if (!candidate.isAbsolute) return admitRelative(candidate)
                if (!candidate.startsWith(root) || !candidate.startsWith(observerDirectory)) {
                    return null
                }
                admitRelative(observerDirectory.relativize(candidate))
            } catch (_: InvalidPathException) {
                null
            }
        }

        private fun admitRelative(relative: Path): ObserverFilePath? {
            val value = relative.toString().replace(relative.fileSystem.separator, "/")
            if (value.isBlank() || value == ".") return null
            if (value == ".." || value.startsWith("../")) return null
            return ObserverFilePath(value)
        }

        private fun isForbiddenPathCharacter(character: Char): Boolean =
            character == '\n' || character == '\r' || character == '\u0000'
    }
}

internal fun String.markdownLabel(): String = replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")

internal fun String.markdownTableCell(): String = replace("|", "\\|")

internal fun String.markdownDestination(): String =
    replace("%", "%25").replace("<", "%3C").replace(">", "%3E").replace("|", "%7C")

private const val MAXIMUM_FILE_LENGTH = 16_384
