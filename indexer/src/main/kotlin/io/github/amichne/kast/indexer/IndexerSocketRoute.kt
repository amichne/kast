package io.github.amichne.kast.indexer

import io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliasReceipt
import io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliases
import io.github.amichne.kast.kernel.Validation
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Retains physical ownership separately from the bounded Unix transport address. */
internal sealed class IndexerSocketRoute(val address: Path, val physical: Path) {
    abstract fun validate(): Validation<IndexerSocketRoute, IndexerTransportFailure>

    private class Aliased(address: Path, private val receipt: InstalledEndpointAliasReceipt) :
        IndexerSocketRoute(address, receipt.physicalDirectory.resolve(address.fileName)) {
        override fun validate(): Validation<IndexerSocketRoute, IndexerTransportFailure> =
            when (receipt.validate()) {
                is Validation.Validated -> Validation.validated(this)
                is Validation.Rejected -> Validation.rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
            }
    }

    private class Direct(address: Path, physical: Path, private val parentKey: Any) :
        IndexerSocketRoute(address, physical) {
        override fun validate(): Validation<IndexerSocketRoute, IndexerTransportFailure> =
            try {
                if (
                    address.parent.toRealPath() == physical.parent &&
                        Files.readAttributes(physical.parent, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                            .fileKey() == parentKey
                )
                    Validation.validated(this)
                else Validation.rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
            } catch (_: Exception) {
                Validation.rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
            }
    }

    companion object {
        fun prepare(address: Path): Validation<IndexerSocketRoute, IndexerTransportFailure> =
            try {
                if (InstalledEndpointAliases.isReservedSocket(address)) {
                    when (val receipt = InstalledEndpointAliases.observe(address)) {
                        is Validation.Validated -> Validation.validated(Aliased(address, receipt.value))
                        is Validation.Rejected -> Validation.rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
                    }
                } else {
                    // Admit the platform's /tmp and /var aliases, never an application-created link.
                    val absolute = address.parent.toAbsolutePath().normalize()
                    val prefix = listOf(Path.of("/tmp"), Path.of("/var")).firstOrNull { absolute.startsWith(it) }
                    val admitted =
                        if (prefix == null) absolute else prefix.toRealPath().resolve(prefix.relativize(absolute))
                    var cursor = admitted.root
                    for (part in admitted) {
                        cursor = cursor.resolve(part)
                        if (Files.isSymbolicLink(cursor))
                            return Validation.rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
                    }
                    Files.createDirectories(admitted)
                    val canonical = admitted.toRealPath()
                    val key =
                        Files.readAttributes(canonical, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                            ?: return Validation.rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
                    Validation.validated(Direct(address, canonical.resolve(address.fileName), key))
                }
            } catch (_: Exception) {
                Validation.rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
            }
    }
}

/** Exact inode captured after publication; replacement never acquires this owner's cleanup right. */
internal class IndexerOwnedFile private constructor(private val path: Path, private val key: Any) {
    fun retire(): IndexerArtifactRetirement =
        try {
            if (Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() != key)
                IndexerArtifactRetirement.UNPROVEN
            else {
                Files.delete(path)
                IndexerArtifactRetirement.PROVEN
            }
        } catch (_: Exception) {
            IndexerArtifactRetirement.UNPROVEN
        }

    companion object {
        fun capture(path: Path): Validation<IndexerOwnedFile, IndexerTransportFailure> =
            try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val key = attributes.fileKey()
                if (attributes.isSymbolicLink || key == null)
                    Validation.rejected(IndexerTransportFailure.SOCKET_BIND_FAILED)
                else Validation.validated(IndexerOwnedFile(path, key))
            } catch (_: Exception) {
                Validation.rejected(IndexerTransportFailure.SOCKET_BIND_FAILED)
            }
    }
}

internal enum class IndexerArtifactRetirement {
    PROVEN,
    UNPROVEN,
}
