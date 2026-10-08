package io.github.amichne.kast.distribution.cli

import com.sun.security.auth.module.UnixSystem
import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class HomeConfigurationCleanupFailure {
    OWNERSHIP_UNPROVEN,
    DOCUMENT_REJECTED,
    CONTENT_CHANGED,
    FOREIGN_CONTENT,
    FILESYSTEM_REJECTED,
}

@Serializable
internal enum class HomeConfigurationArtifact(val fileName: String) {
    CONFIGURATION("config.json"),
    CHECKPOINT("config.last-good.json"),
}

@Serializable
internal data class CapturedHomeConfigurationFile(
    val artifact: HomeConfigurationArtifact,
    val identity: InstallationFilesystemIdentity,
    val digest: UninstallConfigurationDigest,
)

@Serializable
internal sealed interface HomeConfigurationRemovalProof {
    @Serializable @SerialName("ABSENT_CONFIGURATION") data object Absent : HomeConfigurationRemovalProof

    @Serializable
    @SerialName("ADMITTED_CONFIGURATION")
    data class Admitted(
        val home: String,
        val directoryIdentity: InstallationFilesystemIdentity,
        val directoryMode: ConfigurationDirectoryMode,
        val files: List<CapturedHomeConfigurationFile>,
    ) : HomeConfigurationRemovalProof
}

@Serializable
internal enum class ConfigurationDirectoryMode {
    PRIVATE,
    READABLE,
}

@Serializable
@JvmInline
internal value class UninstallConfigurationDigest private constructor(val value: String) {
    companion object {
        fun admit(value: String): Refinement<UninstallConfigurationDigest, HomeConfigurationCleanupFailure> =
            if (validSha256(value)) Refinement.Refined(UninstallConfigurationDigest(value))
            else rejectedConfiguration(HomeConfigurationCleanupFailure.OWNERSHIP_UNPROVEN)
    }
}

/** Existing decoding owns document meaning; retained identities admit only exact removal effects. */
internal fun captureUninstallHomeConfiguration(
    home: Path,
    observe: (UninstallCleanupObservation) -> Unit,
): HomeConfigurationRemovalProof =
    configurationOrReject(homeConfigurationBoundary { admitHomeConfiguration(home) }, observe)

internal fun cleanupUninstallHomeConfiguration(
    home: Path,
    proof: HomeConfigurationRemovalProof,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    observe(UninstallCleanupObservation.Started(UninstallArtifact.HOME_CONFIGURATION))
    configurationOrReject(homeConfigurationBoundary { removeHomeConfiguration(home, proof) }, observe)
    observe(UninstallCleanupObservation.Completed(UninstallArtifact.HOME_CONFIGURATION))
}

private fun admitHomeConfiguration(
    home: Path
): Refinement<HomeConfigurationRemovalProof, HomeConfigurationCleanupFailure> {
    val directory = connectionConfigurationPath(home).parent
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return Refinement.Refined(HomeConfigurationRemovalProof.Absent)
    if (!safeConfigurationDirectories(home, directory))
        return rejectedConfiguration(HomeConfigurationCleanupFailure.OWNERSHIP_UNPROVEN)
    if (!knownConfigurationNames(directory))
        return rejectedConfiguration(HomeConfigurationCleanupFailure.FOREIGN_CONTENT)
    val mode =
        when (Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS)) {
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------") -> ConfigurationDirectoryMode.PRIVATE
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x") -> ConfigurationDirectoryMode.READABLE
            else -> return rejectedConfiguration(HomeConfigurationCleanupFailure.OWNERSHIP_UNPROVEN)
        }
    val identity = uninstallFilesystemIdentity(directory)
    val files = mutableListOf<CapturedHomeConfigurationFile>()
    for (artifact in HomeConfigurationArtifact.entries) {
        val path = directory.resolve(artifact.fileName)
        if (Files.notExists(path, NOFOLLOW_LINKS)) continue
        when (val admitted = captureConfigurationFile(path, artifact)) {
            is Refinement.Refined -> files += admitted.value
            is Refinement.Rejected -> return admitted
        }
    }
    val proof = HomeConfigurationRemovalProof.Admitted(home.toString(), identity, mode, files)
    if (!retainedConfigurationDirectory(home, directory, proof) || !knownConfigurationNames(directory))
        return rejectedConfiguration(HomeConfigurationCleanupFailure.CONTENT_CHANGED)
    return Refinement.Refined(proof)
}

private fun removeHomeConfiguration(
    home: Path,
    proof: HomeConfigurationRemovalProof,
): Refinement<Unit, HomeConfigurationCleanupFailure> {
    val directory = connectionConfigurationPath(home).parent
    return when (proof) {
        HomeConfigurationRemovalProof.Absent ->
            if (Files.notExists(directory, NOFOLLOW_LINKS)) Refinement.Refined(Unit)
            else rejectedConfiguration(HomeConfigurationCleanupFailure.CONTENT_CHANGED)
        is HomeConfigurationRemovalProof.Admitted -> removeAdmittedHomeConfiguration(home, directory, proof)
    }
}

private fun removeAdmittedHomeConfiguration(
    home: Path,
    directory: Path,
    proof: HomeConfigurationRemovalProof.Admitted,
): Refinement<Unit, HomeConfigurationCleanupFailure> {
    if (!validConfigurationProof(home, proof))
        return rejectedConfiguration(HomeConfigurationCleanupFailure.OWNERSHIP_UNPROVEN)
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return Refinement.Refined(Unit)
    if (!retainedConfigurationDirectory(home, directory, proof))
        return rejectedConfiguration(HomeConfigurationCleanupFailure.CONTENT_CHANGED)
    if (!configurationNamesWithin(directory, proof.files.map { it.artifact.fileName }.toSet()))
        return rejectedConfiguration(HomeConfigurationCleanupFailure.FOREIGN_CONTENT)
    for (file in proof.files) when (val current = retainedConfigurationFile(directory, file)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return current
    }
    when (val removed = eraseConfigurationFiles(home, directory, proof)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return removed
    }
    if (!retainedConfigurationDirectory(home, directory, proof))
        return rejectedConfiguration(HomeConfigurationCleanupFailure.CONTENT_CHANGED)
    if (!configurationNamesWithin(directory, emptySet()))
        return rejectedConfiguration(HomeConfigurationCleanupFailure.FOREIGN_CONTENT)
    Files.delete(directory)
    return Refinement.Refined(Unit)
}

private fun eraseConfigurationFiles(
    home: Path,
    directory: Path,
    proof: HomeConfigurationRemovalProof.Admitted,
): Refinement<Unit, HomeConfigurationCleanupFailure> {
    val names = proof.files.map { it.artifact.fileName }.toSet()
    for (file in proof.files) {
        if (!retainedConfigurationDirectory(home, directory, proof) || !configurationNamesWithin(directory, names))
            return rejectedConfiguration(HomeConfigurationCleanupFailure.CONTENT_CHANGED)
        when (val current = retainedConfigurationFile(directory, file)) {
            is Refinement.Refined -> Files.deleteIfExists(directory.resolve(file.artifact.fileName))
            is Refinement.Rejected -> return current
        }
    }
    return Refinement.Refined(Unit)
}

private fun validConfigurationProof(home: Path, proof: HomeConfigurationRemovalProof.Admitted): Boolean =
    proof.home == home.toString() &&
        proof.directoryIdentity.owner == UnixSystem().uid &&
        validConfigurationFileProofs(proof.files)

private fun validConfigurationFileProofs(files: List<CapturedHomeConfigurationFile>): Boolean =
    files.size <= HomeConfigurationArtifact.entries.size &&
        files.map { it.artifact }.toSet().size == files.size &&
        files.all { it.identity.owner == UnixSystem().uid && validSha256(it.digest.value) }

private fun configurationNamesWithin(directory: Path, expected: Set<String>): Boolean =
    Files.newDirectoryStream(directory).use { entries -> entries.all { it.fileName.toString() in expected } }

private fun retainedConfigurationFile(
    directory: Path,
    proof: CapturedHomeConfigurationFile,
): Refinement<Unit, HomeConfigurationCleanupFailure> {
    val file = directory.resolve(proof.artifact.fileName)
    if (Files.notExists(file, NOFOLLOW_LINKS)) return Refinement.Refined(Unit)
    return when (val current = captureConfigurationFile(file, proof.artifact)) {
        is Refinement.Refined ->
            if (current.value == proof) Refinement.Refined(Unit)
            else rejectedConfiguration(HomeConfigurationCleanupFailure.CONTENT_CHANGED)
        is Refinement.Rejected -> current
    }
}

private fun captureConfigurationFile(
    path: Path,
    artifact: HomeConfigurationArtifact,
): Refinement<CapturedHomeConfigurationFile, HomeConfigurationCleanupFailure> {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || path.toRealPath() != path)
        return rejectedConfiguration(HomeConfigurationCleanupFailure.OWNERSHIP_UNPROVEN)
    if (
        uninstallFilesystemIdentity(path).owner != UnixSystem().uid ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) !=
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
    )
        return rejectedConfiguration(HomeConfigurationCleanupFailure.OWNERSHIP_UNPROVEN)
    val identity = uninstallFilesystemIdentity(path)
    val raw =
        readBoundedFile(path, 65536) ?: return rejectedConfiguration(HomeConfigurationCleanupFailure.DOCUMENT_REJECTED)
    if (ConnectionConfiguration.decode(raw) !is ConnectionConfigurationAdmission.Admitted)
        return rejectedConfiguration(HomeConfigurationCleanupFailure.DOCUMENT_REJECTED)
    val digest =
        when (
            val admitted =
                UninstallConfigurationDigest.admit(
                    java.util.HexFormat.of()
                        .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                        )
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    if (uninstallFilesystemIdentity(path) != identity || sha256(path) != digest.value)
        return rejectedConfiguration(HomeConfigurationCleanupFailure.CONTENT_CHANGED)
    return Refinement.Refined(CapturedHomeConfigurationFile(artifact, identity, digest))
}

private fun retainedConfigurationDirectory(
    home: Path,
    directory: Path,
    proof: HomeConfigurationRemovalProof.Admitted,
): Boolean =
    safeConfigurationDirectories(home, directory) &&
        uninstallFilesystemIdentity(directory) == proof.directoryIdentity &&
        Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) ==
            java.nio.file.attribute.PosixFilePermissions.fromString(
                when (proof.directoryMode) {
                    ConfigurationDirectoryMode.PRIVATE -> "rwx------"
                    ConfigurationDirectoryMode.READABLE -> "rwxr-xr-x"
                }
            )

private fun safeConfigurationDirectories(home: Path, directory: Path): Boolean =
    listOf(home, home.resolve(".config"), directory).all { path ->
        Files.isDirectory(path, NOFOLLOW_LINKS) &&
            path.toRealPath() == path &&
            uninstallFilesystemIdentity(path).owner == UnixSystem().uid &&
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).none {
                it == PosixFilePermission.GROUP_WRITE || it == PosixFilePermission.OTHERS_WRITE
            }
    }

private fun knownConfigurationNames(directory: Path): Boolean =
    Files.newDirectoryStream(directory).use { entries ->
        val expected = HomeConfigurationArtifact.entries.map { it.fileName }.toSet()
        entries.all { it.fileName.toString() in expected }
    }

private fun rejectedConfiguration(
    failure: HomeConfigurationCleanupFailure
): Refinement.Rejected<HomeConfigurationCleanupFailure> = Refinement.Rejected(failure)

private inline fun <T> homeConfigurationBoundary(
    effect: () -> Refinement<T, HomeConfigurationCleanupFailure>
): Refinement<T, HomeConfigurationCleanupFailure> =
    try {
        effect()
    } catch (_: java.io.IOException) {
        rejectedConfiguration(HomeConfigurationCleanupFailure.FILESYSTEM_REJECTED)
    }

private fun <T> configurationOrReject(
    result: Refinement<T, HomeConfigurationCleanupFailure>,
    observe: (UninstallCleanupObservation) -> Unit,
): T =
    when (result) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> {
            observe(UninstallCleanupObservation.HomeConfigurationRejected(result.failure))
            throw ManagementRejected(
                "uninstall-home-configuration",
                "configuration cleanup rejected: ${result.failure}; retained cleanup journal",
            )
        }
    }
