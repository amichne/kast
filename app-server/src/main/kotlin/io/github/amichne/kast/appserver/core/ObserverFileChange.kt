package io.github.amichne.kast.appserver.core

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal enum class ObserverFileChangeKind {
    ADD,
    DELETE,
    UPDATE,
}

internal enum class ObserverFileChangeFailure {
    PATH_BLANK,
    PATH_INVALID,
    PATH_ABSOLUTE,
    PATH_NOT_NORMALIZED,
    PATH_ESCAPES_WORKSPACE,
    PATH_CONTROL_CHARACTER,
    DIFF_BLANK,
    DIFF_TOO_LARGE,
    DIFF_CONTROL_CHARACTER,
}

@JvmInline
internal value class ObserverFileChangePath private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): Refinement<ObserverFileChangePath, ObserverFileChangeFailure> {
            if (raw.isBlank()) return Refinement.Rejected(ObserverFileChangeFailure.PATH_BLANK)
            if (raw.any(Char::isISOControl)) {
                return Refinement.Rejected(ObserverFileChangeFailure.PATH_CONTROL_CHARACTER)
            }
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return Refinement.Rejected(ObserverFileChangeFailure.PATH_INVALID)
            }
            if (path.isAbsolute) return Refinement.Rejected(ObserverFileChangeFailure.PATH_ABSOLUTE)
            if (path.any { it.toString() == ".." }) {
                return Refinement.Rejected(ObserverFileChangeFailure.PATH_ESCAPES_WORKSPACE)
            }
            val normalized = path.normalize().toString().replace('\\', '/')
            if (normalized != raw) {
                return Refinement.Rejected(ObserverFileChangeFailure.PATH_NOT_NORMALIZED)
            }
            return Refinement.Refined(ObserverFileChangePath(normalized))
        }
    }
}

@JvmInline
internal value class ObserverFileDiff private constructor(val value: String) {
    companion object {
        private const val MAXIMUM_UTF8_BYTES = BrokerOperationalLimits.maximumObserverDiffBytes

        internal fun admit(raw: String): Refinement<ObserverFileDiff, ObserverFileChangeFailure> =
            when {
                raw.isBlank() -> Refinement.Rejected(ObserverFileChangeFailure.DIFF_BLANK)
                raw.toByteArray(Charsets.UTF_8).size > MAXIMUM_UTF8_BYTES ->
                    Refinement.Rejected(ObserverFileChangeFailure.DIFF_TOO_LARGE)
                raw.any { character ->
                    character.isISOControl() && character !in setOf('\n', '\t')
                } -> Refinement.Rejected(ObserverFileChangeFailure.DIFF_CONTROL_CHARACTER)
                else -> Refinement.Refined(ObserverFileDiff(raw))
            }
    }
}

internal data class ObserverFileChange private constructor(
    val path: ObserverFileChangePath,
    val kind: ObserverFileChangeKind,
    val diff: ObserverFileDiff,
) {
    companion object {
        internal fun admit(
            path: String,
            kind: ObserverFileChangeKind,
            diff: String,
        ): Refinement<ObserverFileChange, ObserverFileChangeFailure> =
            when (val admittedPath = ObserverFileChangePath.admit(path)) {
                is Refinement.Rejected -> admittedPath
                is Refinement.Refined -> when (val admittedDiff = ObserverFileDiff.admit(diff)) {
                    is Refinement.Rejected -> admittedDiff
                    is Refinement.Refined -> Refinement.Refined(
                        ObserverFileChange(admittedPath.value, kind, admittedDiff.value),
                    )
                }
            }
    }
}

internal enum class ObserverFileChangeSetFailure {
    EMPTY,
    TOO_MANY_FILES,
    DUPLICATE_PATH,
}

internal class ObserverFileChangeSet private constructor(entries: List<ObserverFileChange>) {
    val entries: List<ObserverFileChange> = entries.toList()

    companion object {
        private const val MAXIMUM_FILES = BrokerOperationalLimits.maximumObserverChangeFiles

        internal fun admit(
            entries: List<ObserverFileChange>,
        ): Refinement<ObserverFileChangeSet, ObserverFileChangeSetFailure> = when {
            entries.isEmpty() -> Refinement.Rejected(ObserverFileChangeSetFailure.EMPTY)
            entries.size > MAXIMUM_FILES ->
                Refinement.Rejected(ObserverFileChangeSetFailure.TOO_MANY_FILES)
            entries.map { it.path }.distinct().size != entries.size ->
                Refinement.Rejected(ObserverFileChangeSetFailure.DUPLICATE_PATH)
            else -> Refinement.Refined(ObserverFileChangeSet(entries))
        }
    }
}
