package io.github.amichne.kast.idea.backend.diagnostics

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing

internal sealed interface DiagnosticContentObservation {
    @ConsistentCopyVisibility
    data class Unsaved private constructor(
        val psiHash: FileHash,
    ) : DiagnosticContentObservation {
        companion object {
            /** Proof transition: `(NormalizedPath, String) -> Unsaved`. */
            fun fromPsi(filePath: NormalizedPath, psiContent: String): Unsaved = Unsaved(
                FileHash(filePath.value, FileHashing.sha256(psiContent)),
            )
        }
    }

    @ConsistentCopyVisibility
    data class Saved private constructor(
        val vfsHash: FileHash,
        val diskHash: FileHash,
    ) : DiagnosticContentObservation {
        companion object {
            /** Proof transition: `(NormalizedPath, ByteArray, ByteArray) -> Saved`. */
            fun fromContent(
                filePath: NormalizedPath,
                vfsContent: ByteArray,
                diskContent: ByteArray,
            ): Saved = Saved(
                vfsHash = FileHash(filePath.value, FileHashing.sha256(vfsContent)),
                diskHash = FileHash(filePath.value, FileHashing.sha256(diskContent)),
            )
        }
    }

    companion object {
        /**
         * Boundary transition: `(NormalizedPath, String) -> DiagnosticContentObservation.Unsaved`.
         *
         * Refines unsaved PSI text into the exact hash analyzed in the current
         * read epoch; the raw text does not cross this boundary.
         */
        fun unsaved(
            filePath: NormalizedPath,
            psiContent: String,
        ): Unsaved = Unsaved.fromPsi(filePath, psiContent)

        /**
         * Boundary transition:
         * `(NormalizedPath, ByteArray, ByteArray) -> DiagnosticContentObservation.Saved`.
         *
         * Refines saved VFS and filesystem bytes into path-bound hashes. The
         * returned observation retains both authorities so downstream logic
         * cannot silently hash one representation while analyzing the other.
         */
        fun saved(
            filePath: NormalizedPath,
            vfsContent: ByteArray,
            diskContent: ByteArray,
        ): Saved = Saved.fromContent(filePath, vfsContent, diskContent)
    }
}

internal sealed interface DiagnosticContentAuthority {
    data class Current(val fileHash: FileHash) : DiagnosticContentAuthority

    data class VfsBehindDisk(
        val vfsHash: FileHash,
        val diskHash: FileHash,
    ) : DiagnosticContentAuthority

    companion object {
        /**
         * Proof transition: `DiagnosticContentObservation -> DiagnosticContentAuthority`.
         *
         * Unsaved PSI is its own analysis authority. Saved content becomes
         * current only when VFS and disk hashes agree; disagreement remains
         * finite typed staleness and cannot carry a successful diagnostic hash.
         */
        fun derive(observation: DiagnosticContentObservation): DiagnosticContentAuthority = when (observation) {
            is DiagnosticContentObservation.Unsaved -> Current(observation.psiHash)
            is DiagnosticContentObservation.Saved -> if (observation.vfsHash == observation.diskHash) {
                Current(observation.diskHash)
            } else {
                VfsBehindDisk(observation.vfsHash, observation.diskHash)
            }
        }
    }
}
