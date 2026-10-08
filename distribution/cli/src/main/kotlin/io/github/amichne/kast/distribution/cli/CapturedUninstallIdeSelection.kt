package io.github.amichne.kast.distribution.cli

import com.sun.security.auth.module.UnixSystem
import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.SerializationException

/** Mutable configuration retains current-user physical and content proof independently of immutable payloads. */
internal class CapturedUninstallIdeSelection
private constructor(
    val selected: SelectedIdeLaunch.Resolved,
    private val path: Path,
    private val identity: InstallationFilesystemIdentity,
    private val directoryIdentity: InstallationFilesystemIdentity,
    private val digest: UninstallConfigurationDigest,
) {
    fun unchanged(): Boolean =
        ownedSelectionPath(path) &&
            uninstallFilesystemIdentity(path) == identity &&
            uninstallFilesystemIdentity(path.parent) == directoryIdentity &&
            unchangedContents()

    private fun unchangedContents(): Boolean {
        val raw = readBoundedFile(path, MAXIMUM_SELECTION_BYTES) ?: return false
        return selectionDigest(raw) == digest.value
    }

    companion object {
        fun admit(path: Path): Refinement<CapturedUninstallIdeSelection, HostPluginCleanupFailure> {
            if (!ownedSelectionPath(path)) return rejectedSelection()
            val identity = uninstallFilesystemIdentity(path)
            val directoryIdentity = uninstallFilesystemIdentity(path.parent)
            val raw = readBoundedFile(path, MAXIMUM_SELECTION_BYTES) ?: return rejectedSelection()
            val selected =
                try {
                    managementJson.decodeFromString<SelectedIdeLaunch>(raw)
                } catch (_: SerializationException) {
                    return rejectedSelection()
                }
            if (selected !is SelectedIdeLaunch.Resolved) return rejectedSelection()
            val digest =
                when (val admitted = UninstallConfigurationDigest.admit(selectionDigest(raw))) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return rejectedSelection()
                }
            val captured = CapturedUninstallIdeSelection(selected, path, identity, directoryIdentity, digest)
            return if (captured.unchanged()) Refinement.Refined(captured) else rejectedSelection()
        }
    }
}

private fun ownedSelectionPath(path: Path): Boolean {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || path.toRealPath() != path) return false
    if (!Files.isDirectory(path.parent, NOFOLLOW_LINKS) || path.parent.toRealPath() != path.parent) return false
    val uid = UnixSystem().uid
    if (uninstallFilesystemIdentity(path).owner != uid || uninstallFilesystemIdentity(path.parent).owner != uid)
        return false
    return protectedSelectionPermissions(path) && protectedSelectionPermissions(path.parent)
}

private fun protectedSelectionPermissions(path: Path): Boolean =
    Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).none {
        it == PosixFilePermission.GROUP_WRITE || it == PosixFilePermission.OTHERS_WRITE
    }

private fun selectionDigest(raw: String): String =
    java.util.HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))

private fun rejectedSelection() = Refinement.Rejected(HostPluginCleanupFailure.CONFIGURATION_UNPROVEN)

private const val MAXIMUM_SELECTION_BYTES = 65536L
