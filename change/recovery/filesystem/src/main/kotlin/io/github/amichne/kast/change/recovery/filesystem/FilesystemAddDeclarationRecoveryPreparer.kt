package io.github.amichne.kast.change.recovery.filesystem

import io.github.amichne.kast.change.contract.AddDeclarationRecoveryMaterial
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecoveryFailure
import io.github.amichne.kast.change.recovery.spi.AddDeclarationRecoveryPreparer
import io.github.amichne.kast.change.recovery.spi.DurableAddDeclarationRecoveryResult
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64

enum class AddDeclarationRecoveryRootFailure {
    NOT_CANONICAL_ABSOLUTE,
    NOT_REAL_DIRECTORY,
    SYMLINK_NOT_ALLOWED,
}

@JvmInline
internal value class AddDeclarationRecoveryRoot private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition:
         * `Path -> Refinement<AddDeclarationRecoveryRoot, AddDeclarationRecoveryRootFailure>`.
         *
         * Establishes a normalized absolute, real, non-symlink recovery directory. The closed
         * expected failure is `AddDeclarationRecoveryRootFailure`; raw path extraction is permitted
         * only at the recovery filesystem boundary.
         */
        fun admit(
            raw: Path,
        ): Refinement<AddDeclarationRecoveryRoot, AddDeclarationRecoveryRootFailure> {
            if (!raw.isAbsolute || raw.normalize() != raw) {
                return Refinement.Rejected(AddDeclarationRecoveryRootFailure.NOT_CANONICAL_ABSOLUTE)
            }
            if (Files.isSymbolicLink(raw)) {
                return Refinement.Rejected(AddDeclarationRecoveryRootFailure.SYMLINK_NOT_ALLOWED)
            }
            val real = try {
                raw.toRealPath(LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return Refinement.Rejected(AddDeclarationRecoveryRootFailure.NOT_REAL_DIRECTORY)
            } catch (_: SecurityException) {
                return Refinement.Rejected(AddDeclarationRecoveryRootFailure.NOT_REAL_DIRECTORY)
            }
            if (real != raw || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                return Refinement.Rejected(AddDeclarationRecoveryRootFailure.NOT_REAL_DIRECTORY)
            }
            return Refinement.Refined(AddDeclarationRecoveryRoot(real))
        }
    }
}

sealed interface FilesystemAddDeclarationRecoveryPreparerOpenFailure {
    data class InvalidRecoveryRoot(
        val failure: AddDeclarationRecoveryRootFailure,
    ) : FilesystemAddDeclarationRecoveryPreparerOpenFailure
}

sealed interface FilesystemAddDeclarationRecoveryPreparerOpenResult {
    data class Opened(
        val preparer: FilesystemAddDeclarationRecoveryPreparer,
    ) : FilesystemAddDeclarationRecoveryPreparerOpenResult

    data class Rejected(
        val failure: FilesystemAddDeclarationRecoveryPreparerOpenFailure,
    ) : FilesystemAddDeclarationRecoveryPreparerOpenResult
}

class FilesystemAddDeclarationRecoveryPreparer private constructor(
    private val root: AddDeclarationRecoveryRoot,
) : AddDeclarationRecoveryPreparer {
    /**
     * Proof transition:
     * `AddDeclarationRecoveryMaterial -> DurableAddDeclarationRecoveryResult`.
     *
     * A prepared result establishes a PlanId-derived regular artifact whose forced exact bytes
     * equal the revalidated before image. Expected failure is closed by
     * `DurableAddDeclarationRecoveryFailure`; raw bytes and paths are extracted only in this
     * physical adapter.
     */
    override fun prepare(
        material: AddDeclarationRecoveryMaterial,
    ): DurableAddDeclarationRecoveryResult {
        val artifact = root.path.resolve("${material.planId.value}.before")
        val bytes = try {
            Base64.getDecoder().decode(material.beforeImage.contentBase64)
        } catch (_: IllegalArgumentException) {
            return rejected(DurableAddDeclarationRecoveryFailure.STORAGE_UNAVAILABLE)
        }
        return try {
            if (Files.exists(artifact, LinkOption.NOFOLLOW_LINKS)) {
                existing(artifact, material, bytes)
            } else {
                create(artifact, material, bytes)
            }
        } catch (_: IOException) {
            rejected(DurableAddDeclarationRecoveryFailure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            rejected(DurableAddDeclarationRecoveryFailure.STORAGE_UNAVAILABLE)
        }
    }

    private fun create(
        artifact: Path,
        material: AddDeclarationRecoveryMaterial,
        bytes: ByteArray,
    ): DurableAddDeclarationRecoveryResult = try {
        FileChannel.open(
            artifact,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        forceDirectory()
        prepared(material)
    } catch (_: FileAlreadyExistsException) {
        existing(artifact, material, bytes)
    }

    private fun existing(
        artifact: Path,
        material: AddDeclarationRecoveryMaterial,
        bytes: ByteArray,
    ): DurableAddDeclarationRecoveryResult {
        if (Files.isSymbolicLink(artifact) ||
            !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS) ||
            !Files.readAllBytes(artifact).contentEquals(bytes)
        ) {
            return rejected(DurableAddDeclarationRecoveryFailure.EXISTING_ARTIFACT_MISMATCH)
        }
        FileChannel.open(artifact, StandardOpenOption.READ).use { channel -> channel.force(true) }
        forceDirectory()
        return prepared(material)
    }

    private fun forceDirectory() {
        FileChannel.open(root.path, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }

    private fun prepared(
        material: AddDeclarationRecoveryMaterial,
    ): DurableAddDeclarationRecoveryResult.Prepared =
        DurableAddDeclarationRecoveryResult.Prepared(
            DurableAddDeclarationRecovery.fromPreparedMaterial(material),
        )

    private fun rejected(
        failure: DurableAddDeclarationRecoveryFailure,
    ): DurableAddDeclarationRecoveryResult.Rejected =
        DurableAddDeclarationRecoveryResult.Rejected(failure)

    companion object {
        /**
         * Proof transition:
         * `Path -> FilesystemAddDeclarationRecoveryPreparerOpenResult`.
         *
         * An opened result establishes one canonical recovery root for all PlanId-derived
         * artifacts. Expected failure is closed by
         * `FilesystemAddDeclarationRecoveryPreparerOpenFailure`; raw root extraction remains
         * confined to this adapter.
         */
        fun open(
            recoveryRoot: Path,
        ): FilesystemAddDeclarationRecoveryPreparerOpenResult =
            when (val admitted = AddDeclarationRecoveryRoot.admit(recoveryRoot)) {
                is Refinement.Refined -> FilesystemAddDeclarationRecoveryPreparerOpenResult.Opened(
                    FilesystemAddDeclarationRecoveryPreparer(admitted.value),
                )
                is Refinement.Rejected -> FilesystemAddDeclarationRecoveryPreparerOpenResult.Rejected(
                    FilesystemAddDeclarationRecoveryPreparerOpenFailure.InvalidRecoveryRoot(
                        admitted.failure,
                    ),
                )
            }
    }
}
