package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
internal data class RecoveryPlugin(
    val destination: String,
    val candidate: String,
    val candidateIdentity: InstallationFilesystemIdentity,
    val backup: String,
    val priorIdentity: InstallationFilesystemIdentity?,
    val quarantine: String,
)

internal fun validRecoveryPlugin(plugin: RecoveryPlugin?, pluginRoot: String?): Boolean {
    if (plugin == null) return pluginRoot == null || Path.of(pluginRoot).isAbsolute
    val paths =
        when (val admitted = RecoveryPluginPaths.admit(plugin, pluginRoot)) {
            is RecoveryPluginPathAdmission.Admitted -> admitted.paths
            RecoveryPluginPathAdmission.Rejected -> return false
        }
    return validBaselineCandidate(paths, plugin.candidateIdentity) &&
        validPriorBackup(paths.backup, plugin.priorIdentity)
}

internal fun validFinalizingRecoveryPlugin(plugin: RecoveryPlugin?, pluginRoot: String?): Boolean {
    if (plugin == null) return false
    val paths =
        when (val admitted = RecoveryPluginPaths.admit(plugin, pluginRoot)) {
            is RecoveryPluginPathAdmission.Admitted -> admitted.paths
            RecoveryPluginPathAdmission.Rejected -> return false
        }
    return validFinalizingCandidate(paths, plugin.candidateIdentity) &&
        validFinalizingBackup(paths.backup, plugin.priorIdentity)
}

internal fun validPendingRecoveryPlugin(plugin: RecoveryPlugin?, pluginRoot: String?): Boolean {
    if (plugin == null) return false
    val paths =
        when (val admitted = RecoveryPluginPaths.admit(plugin, pluginRoot)) {
            is RecoveryPluginPathAdmission.Admitted -> admitted.paths
            RecoveryPluginPathAdmission.Rejected -> return false
        }
    if (!Files.notExists(paths.quarantine, LinkOption.NOFOLLOW_LINKS)) return false
    val candidate = pendingCandidatePlacement(paths, plugin.candidateIdentity)
    if (candidate == PendingCandidatePlacement.REJECTED) return false
    return if (plugin.priorIdentity == null) validAbsentPrior(paths, candidate)
    else validPendingPrior(paths, plugin.priorIdentity, candidate)
}

private sealed interface RecoveryPluginPathAdmission {
    data class Admitted(val paths: RecoveryPluginPaths) : RecoveryPluginPathAdmission

    data object Rejected : RecoveryPluginPathAdmission
}

/** One admitted destination and its three same-token, same-parent transaction paths. */
private class RecoveryPluginPaths
private constructor(
    val destination: Path,
    val candidate: Path,
    val backup: Path,
    val quarantine: Path,
) {
    companion object {
        fun admit(plugin: RecoveryPlugin, pluginRoot: String?): RecoveryPluginPathAdmission {
            val destination = Path.of(plugin.destination)
            if (!validPluginDestination(destination, pluginRoot)) return RecoveryPluginPathAdmission.Rejected
            val paths = listOf(Path.of(plugin.candidate), Path.of(plugin.backup), Path.of(plugin.quarantine))
            if (!validPluginTransactionPaths(destination, paths)) return RecoveryPluginPathAdmission.Rejected
            return RecoveryPluginPathAdmission.Admitted(
                RecoveryPluginPaths(
                    destination = destination,
                    candidate = paths[0],
                    backup = paths[1],
                    quarantine = paths[2],
                )
            )
        }
    }
}

private fun validPluginDestination(destination: Path, pluginRoot: String?): Boolean =
    canonicalPluginDestination(destination) &&
        pluginRoot == destination.parent.toString() &&
        physicalPluginDirectory(destination.parent)

private fun canonicalPluginDestination(destination: Path): Boolean =
    destination.isAbsolute &&
        destination.normalize() == destination &&
        destination.fileName.toString() == "kast-ide-hosted"

private fun validPluginTransactionPaths(destination: Path, paths: List<Path>): Boolean {
    val retained = destination.parent.parent.resolve(".kast-plugin-recovery")
    val prefixes = listOf(".kast-ide-hosted.install-", ".kast-ide-hosted.baseline-", ".kast-ide-hosted.detached-")
    val token = paths[0].fileName.toString().removePrefix(prefixes[0])
    if (!token.matches(Regex("[0-9a-f]{32}"))) return false
    val allowedParents = setOf(destination.parent, retained)
    if (paths.indices.any { !validPluginTransactionPath(paths[it], prefixes[it] + token, allowedParents) }) return false
    return paths.map(Path::getParent).distinct().size == 1
}

private fun validPluginTransactionPath(path: Path, name: String, allowedParents: Set<Path>): Boolean =
    path.normalize() == path && path.fileName.toString() == name && path.parent in allowedParents

private fun validBaselineCandidate(paths: RecoveryPluginPaths, expected: InstallationFilesystemIdentity): Boolean {
    val active =
        when {
            Files.isDirectory(paths.destination, LinkOption.NOFOLLOW_LINKS) -> paths.destination
            Files.isDirectory(paths.candidate, LinkOption.NOFOLLOW_LINKS) -> paths.candidate
            else -> return false
        }
    return matchesPluginDirectory(active, expected)
}

private fun validPriorBackup(backup: Path, expected: InstallationFilesystemIdentity?): Boolean =
    expected == null || matchesPluginDirectory(backup, expected)

private fun validFinalizingCandidate(paths: RecoveryPluginPaths, expected: InstallationFilesystemIdentity): Boolean =
    matchesPluginDirectory(paths.destination, expected) &&
        Files.notExists(paths.candidate, LinkOption.NOFOLLOW_LINKS) &&
        Files.notExists(paths.quarantine, LinkOption.NOFOLLOW_LINKS)

private fun validFinalizingBackup(backup: Path, expected: InstallationFilesystemIdentity?): Boolean =
    Files.notExists(backup, LinkOption.NOFOLLOW_LINKS) || (expected != null && matchesPluginDirectory(backup, expected))

private enum class PendingCandidatePlacement {
    PENDING,
    ACTIVE,
    REJECTED,
}

private fun pendingCandidatePlacement(
    paths: RecoveryPluginPaths,
    expected: InstallationFilesystemIdentity,
): PendingCandidatePlacement {
    val pending = matchesPluginDirectory(paths.candidate, expected)
    val active = matchesPluginDirectory(paths.destination, expected)
    if (pending == active) return PendingCandidatePlacement.REJECTED
    if (pending) return PendingCandidatePlacement.PENDING
    return if (Files.notExists(paths.candidate, LinkOption.NOFOLLOW_LINKS)) PendingCandidatePlacement.ACTIVE
    else PendingCandidatePlacement.REJECTED
}

private fun validAbsentPrior(paths: RecoveryPluginPaths, candidate: PendingCandidatePlacement): Boolean =
    Files.notExists(paths.backup, LinkOption.NOFOLLOW_LINKS) &&
        (candidate == PendingCandidatePlacement.ACTIVE || Files.notExists(paths.destination, LinkOption.NOFOLLOW_LINKS))

private fun validPendingPrior(
    paths: RecoveryPluginPaths,
    expected: InstallationFilesystemIdentity,
    candidate: PendingCandidatePlacement,
): Boolean {
    val active = matchesPluginDirectory(paths.destination, expected)
    val backedUp = matchesPluginDirectory(paths.backup, expected)
    if (active == backedUp) return false
    return if (active)
        candidate == PendingCandidatePlacement.PENDING && Files.notExists(paths.backup, LinkOption.NOFOLLOW_LINKS)
    else candidate == PendingCandidatePlacement.ACTIVE || Files.notExists(paths.destination, LinkOption.NOFOLLOW_LINKS)
}

private fun matchesPluginDirectory(path: Path, expected: InstallationFilesystemIdentity): Boolean =
    physicalPluginDirectory(path) && pluginIdentity(path) == expected

private fun physicalPluginDirectory(path: Path): Boolean =
    path.isAbsolute &&
        path.normalize() == path &&
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
        path.toRealPath() == path

private fun pluginIdentity(path: Path): InstallationFilesystemIdentity =
    InstallationFilesystemIdentity(
        (Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
    )
