package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.validation.FileHashing

internal sealed interface SecureWorkspaceFileObservation {
    data object Absent : SecureWorkspaceFileObservation

    class Present private constructor(bytes: ByteArray) : SecureWorkspaceFileObservation {
        private val storedBytes = bytes.copyOf()

        val bytes: ByteArray
            get() = storedBytes.copyOf()

        val sha256: String = FileHashing.sha256(storedBytes)

        companion object {
            fun of(bytes: ByteArray): Present = Present(bytes)
        }
    }
}
