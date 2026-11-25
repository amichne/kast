package io.github.amichne.kast.runtime.hosted.lifecycle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal enum class HostStateReplacement {
    REPLACED,
    FAILED,
}

internal enum class HostStateRemoval {
    REMOVED,
    ABSENT,
    FAILED,
}

/**
 * The only Host writer in a managed Host's admitted private state directory. A replacement is atomic, so a reader sees
 * the earlier content or the new content, and a failure leaves the earlier content.
 */
internal class HostStateFile(private val state: Path, private val name: String) {
    fun replace(content: String): HostStateReplacement {
        var temporary: Path? = null
        return try {
            temporary = Files.createTempFile(state, ".$name-", ".tmp")
            Files.writeString(temporary, content)
            Files.move(
                temporary,
                state.resolve(name),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            HostStateReplacement.REPLACED
        } catch (_: java.io.IOException) {
            temporary?.let(::discard)
            HostStateReplacement.FAILED
        }
    }

    fun remove(): HostStateRemoval =
        try {
            if (Files.deleteIfExists(state.resolve(name))) HostStateRemoval.REMOVED else HostStateRemoval.ABSENT
        } catch (_: java.io.IOException) {
            HostStateRemoval.FAILED
        }

    private fun discard(temporary: Path) {
        try {
            Files.deleteIfExists(temporary)
        } catch (_: java.io.IOException) {
            // The replacement already FAILED; a leftover temporary file is never read as a record.
        }
    }
}
