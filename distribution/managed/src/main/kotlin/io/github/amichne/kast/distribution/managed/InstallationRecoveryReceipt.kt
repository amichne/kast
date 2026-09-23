package io.github.amichne.kast.distribution.managed

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class RecoveryIdentity(val device: Long, val inode: Long, val owner: Long)

@Serializable private data class RecoveryLink(val path: String, val target: String, val priorTarget: String?)

@Serializable
private enum class RecoveryStage {
    @SerialName("Prepared") PREPARED,
    @SerialName("Active") ACTIVE,
    @SerialName("DetachedWithUnresolvedState") DETACHED,
    @SerialName("CleanBaselineRestored") CLEAN,
}

@Serializable
private data class PreparedRecoveryReceipt(
    val schemaVersion: Int = CURRENT_RECOVERY_SCHEMA_VERSION,
    val installation: String,
    val installationIdentity: RecoveryIdentity,
    val links: List<RecoveryLink>,
    val priorInstallation: String?,
    val plugin: RecoveryPlugin? = null,
    val pluginRoot: String? = null,
    val stage: RecoveryStage = RecoveryStage.PREPARED,
)

@Serializable
private data class RecoveryPlugin(
    val destination: String,
    val candidate: String,
    val candidateIdentity: RecoveryIdentity,
    val backup: String,
    val priorIdentity: RecoveryIdentity?,
    val quarantine: String,
)

private const val MAXIMUM_RECOVERY_RECEIPT_BYTES = 65_536
private const val LEGACY_RECOVERY_SCHEMA_VERSION = 1
private const val CURRENT_RECOVERY_SCHEMA_VERSION = 2
private const val LEGACY_RECOVERY_LINK_COUNT = 3
private const val CURRENT_RECOVERY_LINK_COUNT = 1

sealed interface InstallationRecoveryPreparation {
    data object Prepared : InstallationRecoveryPreparation

    data object Rejected : InstallationRecoveryPreparation
}

enum class InstallationRecoveryHistory {
    RETAIN,
    REPLACE,
}

/** Called with the activation lock held, before replacing any launcher. */
fun prepareInstallationRecovery(
    root: Path,
    history: InstallationRecoveryHistory = InstallationRecoveryHistory.RETAIN,
): InstallationRecoveryPreparation =
    try {
        prepareRecoveryFiles(root, history)
        InstallationRecoveryPreparation.Prepared
    } catch (_: java.io.IOException) {
        InstallationRecoveryPreparation.Rejected
    } catch (_: kotlinx.serialization.SerializationException) {
        InstallationRecoveryPreparation.Rejected
    } catch (_: SecurityException) {
        InstallationRecoveryPreparation.Rejected
    }

private fun prepareRecoveryFiles(root: Path, history: InstallationRecoveryHistory) {
    val recovery = root.parent.parent.resolve("recovery")
    val bundle = recovery.resolve(root.fileName)
    listOf(recovery, bundle).forEach(::prepareRecoveryDirectory)
    val receipt = bundle.resolve("receipt.json")
    if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
        val existing = validateExistingReceipt(root, receipt)
        if (history == InstallationRecoveryHistory.REPLACE && existing.priorInstallation != null)
            writeRecoveryReceipt(receipt, existing.copy(priorInstallation = null))
        return
    }
    val document =
        recoveryReceipt(root).let {
            when (history) {
                InstallationRecoveryHistory.RETAIN -> it
                InstallationRecoveryHistory.REPLACE -> it.copy(priorInstallation = null)
            }
        }
    copyRecoveryBundle(root, bundle)
    writeRecoveryReceipt(receipt, document)
    FileChannel.open(recovery, StandardOpenOption.READ).use { it.force(true) }
}

private fun prepareRecoveryDirectory(directory: Path) {
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
    if (directory.toRealPath() != directory || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw java.io.IOException("recovery directory rejected")
    }
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
}

private fun validateExistingReceipt(root: Path, receipt: Path): PreparedRecoveryReceipt {
    if (
        !Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) || Files.size(receipt) > MAXIMUM_RECOVERY_RECEIPT_BYTES
    ) {
        throw java.io.IOException("recovery receipt rejected")
    }
    val existing = Json.decodeFromString(PreparedRecoveryReceipt.serializer(), Files.readString(receipt))
    val identityMatches =
        existing.installation == root.toString() && existing.installationIdentity == observeRecoveryIdentity(root)
    if (
        !validRecoveryLayout(existing) ||
            !identityMatches ||
            existing.stage !in setOf(RecoveryStage.PREPARED, RecoveryStage.ACTIVE)
    ) {
        throw java.io.IOException("recovery receipt rejected")
    }
    return existing
}

private fun validRecoveryLayout(receipt: PreparedRecoveryReceipt): Boolean =
    when (receipt.schemaVersion) {
        LEGACY_RECOVERY_SCHEMA_VERSION -> receipt.links.size == LEGACY_RECOVERY_LINK_COUNT
        CURRENT_RECOVERY_SCHEMA_VERSION -> receipt.links.size == CURRENT_RECOVERY_LINK_COUNT
        else -> false
    }

private fun recoveryLinkTarget(path: Path): String? =
    when {
        Files.isSymbolicLink(path) -> Files.readSymbolicLink(path).toString()
        Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> null
        else -> throw java.io.IOException("recovery anchor rejected")
    }

private fun recoveryReceipt(root: Path): PreparedRecoveryReceipt {
    val outer = root.parent.parent
    val current = outer.resolve("current")
    val prior = recoveryLinkTarget(current)
    return PreparedRecoveryReceipt(
        installation = root.toString(),
        installationIdentity = observeRecoveryIdentity(root),
        links = listOf(RecoveryLink(current.toString(), "versions/${root.fileName}", prior)),
        priorInstallation = prior?.let { outer.resolve(it).toString() },
    )
}

private fun copyRecoveryBundle(root: Path, bundle: Path) {
    for (name in listOf("installation-recovery.py", "installation-lifecycle.py")) {
        val source = root.resolve("share/kast/$name")
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw java.io.IOException("recovery bundle missing")
        Files.copy(source, bundle.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        Files.setPosixFilePermissions(bundle.resolve(name), PosixFilePermissions.fromString("rw-------"))
        forceRecoveryFile(bundle.resolve(name))
    }
}

private fun writeRecoveryReceipt(receipt: Path, document: PreparedRecoveryReceipt) {
    val bundle = receipt.parent
    val temporary = Files.createTempFile(bundle, ".receipt-", ".tmp")
    try {
        Files.writeString(
            temporary,
            Json { encodeDefaults = true }.encodeToString(PreparedRecoveryReceipt.serializer(), document),
        )
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
        forceRecoveryFile(temporary)
        Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(bundle, StandardOpenOption.READ).use { it.force(true) }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun forceRecoveryFile(path: Path) {
    FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
}

private fun observeRecoveryIdentity(root: Path) =
    RecoveryIdentity(
        (Files.getAttribute(root, "unix:dev", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(root, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(root, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
    )
