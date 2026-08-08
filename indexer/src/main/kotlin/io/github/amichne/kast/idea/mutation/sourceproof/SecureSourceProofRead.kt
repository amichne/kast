package io.github.amichne.kast.idea.mutation

import com.sun.jna.Memory
import com.sun.jna.NativeLong
import java.io.ByteArrayOutputStream
import java.nio.file.Path

internal object SecureSourceProofRead {
    fun fileBytes(path: Path): ByteArray {
        val normalizedPath = path.toAbsolutePath().normalize()
        val platform = PosixPlatform.current()
            ?: error("Secure source-proof reads require a supported POSIX runtime")
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
                requireRegularFile(fileDescriptor, platform)
                readBytes(fileDescriptor)
            }
        } finally {
            opened.asReversed().forEach(NativeDescriptor::close)
        }
    }

    private fun requireRegularFile(descriptor: NativeDescriptor, platform: PosixPlatform) {
        val status = Memory(STAT_BUFFER_SIZE)
        if (api.fstat(descriptor.value, status) < 0 ||
            platform.readStatus(status).mode.fileType != NativeFileType.REGULAR
        ) error("Source-proof path is not a regular file")
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
