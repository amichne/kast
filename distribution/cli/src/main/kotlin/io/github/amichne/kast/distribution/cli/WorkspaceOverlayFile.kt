package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.InstalledWorkspaceConfigurationIngress
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions

/** Replaces the canonical workspace overlay under the caller's installation locks. */
internal object WorkspaceOverlayFile {
    fun replace(installation: Path, workspace: CanonicalRoot, content: String) {
        val file = InstalledWorkspaceConfigurationIngress.workspaceFile(installation, workspace.path)
        try {
            createOverlayDirectories(installation, file.parent)
            val privateMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            val temporary = Files.createTempFile(file.parent, ".environment-", ".tmp", privateMode)
            try {
                Files.writeString(temporary, content)
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (_: IOException) {
            throw ManagementRejected("workspace-config", "WRITE_UNAVAILABLE")
        } catch (_: SecurityException) {
            throw ManagementRejected("workspace-config", "WRITE_UNAVAILABLE")
        }
    }

    private fun createOverlayDirectories(installation: Path, parent: Path) {
        var directory = installation
        for (component in installation.relativize(parent)) {
            directory = directory.resolve(component)
            if (Files.notExists(directory, NOFOLLOW_LINKS))
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(containerDirectoryMode))
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || directory.toRealPath() != directory)
                throw ManagementRejected("workspace-config", "NOT_REGULAR")
        }
    }
}
