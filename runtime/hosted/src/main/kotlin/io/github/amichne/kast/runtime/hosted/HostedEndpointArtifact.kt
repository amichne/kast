package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal

internal enum class HostedEndpointArtifactKind {
    DIRECTORY,
    FILE,
    SOCKET,
}

/** File identity is retained through the final deletion check; paths alone confer no ownership. */
internal class HostedEndpointArtifact
private constructor(
    val path: Path,
    val owner: UserPrincipal,
    private val key: Any,
    private val kind: HostedEndpointArtifactKind,
    private val size: Long,
    private val modified: java.nio.file.attribute.FileTime,
) {
    fun verify(): Refinement<Unit, HostedEndpointFailure> {
        val current =
            when (val observed = capture(path, kind)) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected -> return observed
            }
        if (current.owner != owner || current.key != key)
            return Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
        return if (current.size == size && current.modified == modified) Refinement.Refined(Unit)
        else Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
    }

    companion object {
        fun socket(directory: Path): Refinement<HostedEndpointArtifact, HostedEndpointFailure> =
            capture(directory.resolve("host.sock"), HostedEndpointArtifactKind.SOCKET)

        fun descriptor(directory: Path): Refinement<HostedEndpointArtifact, HostedEndpointFailure> =
            capture(directory.resolve("endpoint.json"), HostedEndpointArtifactKind.FILE)

        fun capture(
            path: Path,
            kind: HostedEndpointArtifactKind,
        ): Refinement<HostedEndpointArtifact, HostedEndpointFailure> {
            val attributes = Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            val mode = Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int
            val expected =
                when (kind) {
                    HostedEndpointArtifactKind.DIRECTORY -> 0x4000
                    HostedEndpointArtifactKind.FILE -> 0x8000
                    HostedEndpointArtifactKind.SOCKET -> 0xC000
                }
            if (mode and UNIX_FILE_TYPE_MASK != expected || attributes.isSymbolicLink)
                return Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
            if (attributes.permissions().any { it in forbiddenPermissions(kind) })
                return Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
            if (
                kind != HostedEndpointArtifactKind.DIRECTORY &&
                    Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) != 1
            )
                return Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
            val currentUser =
                path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            if (attributes.owner() != currentUser) return Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
            val key = attributes.fileKey() ?: return Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
            return Refinement.Refined(
                HostedEndpointArtifact(
                    path = path,
                    owner = attributes.owner(),
                    key = key,
                    kind = kind,
                    size = attributes.size(),
                    modified = attributes.lastModifiedTime(),
                )
            )
        }

        private fun forbiddenPermissions(kind: HostedEndpointArtifactKind): Set<PosixFilePermission> =
            when (kind) {
                HostedEndpointArtifactKind.DIRECTORY ->
                    setOf(
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE,
                        PosixFilePermission.OTHERS_EXECUTE,
                    )
                HostedEndpointArtifactKind.FILE,
                HostedEndpointArtifactKind.SOCKET ->
                    setOf(
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.OTHERS_WRITE,
                    )
            }
    }
}

private const val UNIX_FILE_TYPE_MASK = 0xF000
