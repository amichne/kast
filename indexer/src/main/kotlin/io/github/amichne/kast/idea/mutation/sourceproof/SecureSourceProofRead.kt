package io.github.amichne.kast.idea.mutation

import com.sun.jna.Memory
import com.sun.jna.NativeLong
import io.github.amichne.kast.api.validation.FileHashing
import java.io.ByteArrayOutputStream
import java.nio.file.Path

internal sealed interface SecureSourceProofReadOutcome {
    @JvmInline
    value class Read private constructor(val sha256: String) : SecureSourceProofReadOutcome {
        companion object {
            /**
             * Proof transition: `ByteArray -> SecureSourceProofReadOutcome.Read`.
             *
             * Retains the exact SHA-256 identity of bytes read through the no-follow adapter. Raw
             * bytes exist only inside `SecureSourceProofRead` and are not exposed by the result.
             */
            fun from(bytes: ByteArray): Read = Read(FileHashing.sha256(bytes))
        }
    }

    enum class Unavailable : SecureSourceProofReadOutcome {
        UNSUPPORTED_PLATFORM,
        NATIVE_PRIMITIVES_UNAVAILABLE,
        UNSAFE_OR_UNREADABLE_PATH,
    }
}

internal object SecureSourceProofRead {
    /**
     * Proof transition: `Path -> SecureSourceProofReadOutcome`.
     *
     * `Read` proves that one regular file was opened through no-follow descriptors and carries the
     * hash of its exact bytes. `Unavailable` is the closed expected failure when the platform,
     * native primitives, or path cannot provide that proof. Raw bytes exist only inside this
     * filesystem adapter.
     */
    fun sha256(path: Path): SecureSourceProofReadOutcome {
        val normalizedPath = path.toAbsolutePath().normalize()
        val platform = PosixPlatform.current()
            ?: return SecureSourceProofReadOutcome.Unavailable.UNSUPPORTED_PLATFORM
        return try {
            SecureSourceProofReadOutcome.Read.from(readFileBytes(normalizedPath, platform))
        } catch (_: LinkageError) {
            SecureSourceProofReadOutcome.Unavailable.NATIVE_PRIMITIVES_UNAVAILABLE
        } catch (_: Exception) {
            SecureSourceProofReadOutcome.Unavailable.UNSAFE_OR_UNREADABLE_PATH
        }
    }

    private fun readFileBytes(normalizedPath: Path, platform: PosixPlatform): ByteArray {
        val filesystemRoot = checkNotNull(normalizedPath.root) {
            "Absolute source-proof file must have a filesystem root"
        }
        val rootDescriptorValue = api.open(filesystemRoot.toString(), platform.directoryFlags, 0)
        if (rootDescriptorValue < 0) error("Could not open the source-proof filesystem root")
        return NativeDescriptor(api, rootDescriptorValue).use { rootDescriptor ->
            readRelativeToRoot(normalizedPath, filesystemRoot, rootDescriptor, platform)
        }
    }

    private fun readRelativeToRoot(
        path: Path,
        filesystemRoot: Path,
        rootDescriptor: NativeDescriptor,
        platform: PosixPlatform,
    ): ByteArray {
        var current = rootDescriptor
        val opened = mutableListOf<NativeDescriptor>()
        try {
            val components = filesystemRoot.relativize(path).toList()
            components.dropLast(1).forEach { component ->
                val descriptorValue = api.openat(current.value, component.toString(), platform.directoryFlags, 0)
                if (descriptorValue < 0) error("Unsafe source-proof directory component")
                val next = NativeDescriptor(api, descriptorValue)
                opened += next
                current = next
            }
            val fileDescriptorValue = api.openat(
                current.value,
                components.last().toString(),
                platform.readFileFlags,
                0,
            )
            if (fileDescriptorValue < 0) error("Unsafe source-proof file component")
            return NativeDescriptor(api, fileDescriptorValue).use { fileDescriptor ->
                val status = Memory(STAT_BUFFER_SIZE)
                if (api.fstat(fileDescriptor.value, status) < 0 ||
                    platform.readStatus(status).mode.fileType != NativeFileType.REGULAR
                ) error("Source-proof path is not a regular file")
                readBytes(fileDescriptor)
            }
        } finally {
            opened.asReversed().forEach(NativeDescriptor::close)
        }
    }

    private fun readBytes(descriptor: NativeDescriptor): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = Memory(BUFFER_SIZE.toLong())
        while (true) {
            val read = api.read(descriptor.value, buffer, NativeLong(BUFFER_SIZE.toLong())).toLong()
            if (read == 0L) return output.toByteArray()
            if (read < 0L) error("Could not read the source-proof file descriptor")
            output.write(buffer.getByteArray(0, read.toInt()))
        }
    }
}
