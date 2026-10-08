package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private enum class RecoveryStage {
    @SerialName("Prepared") PREPARED,
    @SerialName("Active") ACTIVE,
    @SerialName("PluginPrepared") PLUGIN_PREPARED,
    @SerialName("UpgradeFinalizing") FINALIZING,
    @SerialName("DetachedWithUnresolvedState") DETACHED,
    @SerialName("CleanBaselineRestored") CLEAN,
}

@Serializable
private data class RecoveryReceipt(
    @Required val schemaVersion: Int = INSTALLATION_MANIFEST_SCHEMA_VERSION,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    @Required val plugin: RecoveryPlugin? = null,
    @Required val pluginRoot: String? = null,
    @Required val stage: RecoveryStage = RecoveryStage.PREPARED,
)

/** This shape is admitted only when explicitly migrating an old versioned installation. */
@Serializable
private data class LegacyRecoveryReceipt(
    val schemaVersion: Int,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val links: List<LegacyRecoveryLink>,
    val priorInstallation: String?,
    val plugin: RecoveryPlugin? = null,
    val pluginRoot: String? = null,
    val stage: RecoveryStage = RecoveryStage.PREPARED,
)

@Serializable private data class LegacyRecoveryLink(val path: String, val target: String, val priorTarget: String?)

private const val MAXIMUM_RECOVERY_RECEIPT_BYTES = 65_536

sealed interface InstallationRecoveryPreparation {
    data object Prepared : InstallationRecoveryPreparation

    data object Rejected : InstallationRecoveryPreparation
}

sealed interface InstallationRecoveryAdmission {
    data class Admitted(val baseline: InstallationRecoveryBaseline) : InstallationRecoveryAdmission

    data class Pending(val installation: Path) : InstallationRecoveryAdmission

    data object Rejected : InstallationRecoveryAdmission
}

/** Retains the admitted control bundle; historical host evidence remains in the prior bundle. */
class InstallationRecoveryBaseline
private constructor(
    val installation: Path,
    val bundle: Path,
) {
    internal fun prepare(root: Path): InstallationRecoveryPreparation = prepareRecovery(root, null, null)

    companion object {
        internal fun admit(root: Path): InstallationRecoveryAdmission =
            try {
                val slot = RecoverySlot.observe(root)
                if (!slot.validRoot()) InstallationRecoveryAdmission.Rejected
                else
                    when (val decoded = readRecoveryDocument(slot)) {
                        RecoveryDocumentRead.Rejected -> InstallationRecoveryAdmission.Rejected
                        is RecoveryDocumentRead.Decoded -> admitDocument(slot, decoded.receipt)
                    }
            } catch (_: java.io.IOException) {
                InstallationRecoveryAdmission.Rejected
            } catch (_: kotlinx.serialization.SerializationException) {
                InstallationRecoveryAdmission.Rejected
            } catch (_: SecurityException) {
                InstallationRecoveryAdmission.Rejected
            } catch (_: java.nio.file.InvalidPathException) {
                InstallationRecoveryAdmission.Rejected
            }

        private fun admitDocument(slot: RecoverySlot, receipt: RecoveryReceipt): InstallationRecoveryAdmission {
            if (!matchesInstallation(receipt, slot.root)) return InstallationRecoveryAdmission.Rejected
            return when (recoveryTransfer(receipt, slot.layout)) {
                RecoveryTransfer.PENDING -> InstallationRecoveryAdmission.Pending(slot.root)
                RecoveryTransfer.BASELINE ->
                    InstallationRecoveryAdmission.Admitted(
                        InstallationRecoveryBaseline(
                            installation = slot.root,
                            bundle = slot.bundle,
                        )
                    )
                RecoveryTransfer.REJECTED -> InstallationRecoveryAdmission.Rejected
            }
        }
    }
}

private enum class RecoveryLayout {
    DIRECT,
    LEGACY,
}

private data class RecoverySlot(val root: Path, val outer: Path, val bundle: Path, val layout: RecoveryLayout) {
    fun validRoot(): Boolean =
        physicalDirectory(root) && (layout == RecoveryLayout.LEGACY || root.fileName.toString() == "installation")

    companion object {
        fun observe(root: Path): RecoverySlot {
            val layout =
                if (root.parent.fileName.toString() == "versions") RecoveryLayout.LEGACY else RecoveryLayout.DIRECT
            val outer = if (layout == RecoveryLayout.LEGACY) root.parent.parent else root.parent
            val name = if (layout == RecoveryLayout.LEGACY) root.fileName else Path.of("installation")
            return RecoverySlot(
                root = root,
                outer = outer,
                bundle = outer.resolve("recovery").resolve(name),
                layout = layout,
            )
        }
    }
}

private sealed interface RecoveryDocumentRead {
    data class Decoded(val receipt: RecoveryReceipt) : RecoveryDocumentRead

    data object Rejected : RecoveryDocumentRead
}

private fun readRecoveryDocument(slot: RecoverySlot): RecoveryDocumentRead {
    val bytes = readReceipt(slot.bundle.resolve("receipt.json"))
    return when (slot.layout) {
        RecoveryLayout.DIRECT ->
            RecoveryDocumentRead.Decoded(recoveryJson.decodeFromString(RecoveryReceipt.serializer(), bytes))
        RecoveryLayout.LEGACY -> readLegacyRecoveryDocument(slot, bytes)
    }
}

private fun readLegacyRecoveryDocument(slot: RecoverySlot, bytes: String): RecoveryDocumentRead {
    val receipt = recoveryJson.decodeFromString(LegacyRecoveryReceipt.serializer(), bytes)
    if (!validLegacyRecoverySelection(slot, receipt)) return RecoveryDocumentRead.Rejected
    return RecoveryDocumentRead.Decoded(
        RecoveryReceipt(
            installation = receipt.installation,
            installationIdentity = receipt.installationIdentity,
            plugin = receipt.plugin,
            pluginRoot = receipt.pluginRoot,
            stage = receipt.stage,
        )
    )
}

private fun validLegacyRecoverySelection(slot: RecoverySlot, receipt: LegacyRecoveryReceipt): Boolean {
    if (receipt.schemaVersion !in 1..2) return false
    val count = if (receipt.schemaVersion == 1) LEGACY_FIRST_SCHEMA_LINK_COUNT else 1
    if (receipt.links.size != count) return false
    val expected = slot.outer.resolve("current")
    val target = "versions/${slot.root.fileName}"
    return matchesLegacyRecoveryLink(receipt.links.first(), expected, target) &&
        Files.isSymbolicLink(expected) &&
        Files.readSymbolicLink(expected).toString() == target
}

private const val LEGACY_FIRST_SCHEMA_LINK_COUNT = 3

private fun matchesLegacyRecoveryLink(link: LegacyRecoveryLink, expected: Path, target: String): Boolean =
    link.path == expected.toString() && link.target == target

private fun matchesInstallation(receipt: RecoveryReceipt, root: Path): Boolean =
    receipt.schemaVersion == INSTALLATION_MANIFEST_SCHEMA_VERSION &&
        receipt.installation == root.toString() &&
        receipt.installationIdentity == identity(root)

private enum class RecoveryTransfer {
    BASELINE,
    PENDING,
    REJECTED,
}

private fun recoveryTransfer(receipt: RecoveryReceipt, layout: RecoveryLayout): RecoveryTransfer =
    when (receipt.stage) {
        RecoveryStage.PREPARED,
        RecoveryStage.ACTIVE ->
            if (validRecoveryPlugin(receipt.plugin, receipt.pluginRoot)) RecoveryTransfer.BASELINE
            else RecoveryTransfer.REJECTED
        RecoveryStage.PLUGIN_PREPARED,
        RecoveryStage.FINALIZING -> pendingRecoveryTransfer(receipt, layout)
        RecoveryStage.DETACHED,
        RecoveryStage.CLEAN -> RecoveryTransfer.REJECTED
    }

private fun pendingRecoveryTransfer(receipt: RecoveryReceipt, layout: RecoveryLayout): RecoveryTransfer {
    if (layout != RecoveryLayout.DIRECT) return RecoveryTransfer.REJECTED
    val valid =
        when (receipt.stage) {
            RecoveryStage.PLUGIN_PREPARED -> validPendingRecoveryPlugin(receipt.plugin, receipt.pluginRoot)
            RecoveryStage.FINALIZING -> validFinalizingRecoveryPlugin(receipt.plugin, receipt.pluginRoot)
            RecoveryStage.PREPARED,
            RecoveryStage.ACTIVE,
            RecoveryStage.DETACHED,
            RecoveryStage.CLEAN -> false
        }
    return if (valid) RecoveryTransfer.PENDING else RecoveryTransfer.REJECTED
}

fun admitInstallationRecovery(root: Path): InstallationRecoveryAdmission = InstallationRecoveryBaseline.admit(root)

/** Capture terminal receipt ownership while its physical installation still exists. */
internal fun admitRecoveryRemovalIdentity(
    root: Path
): io.github.amichne.kast.kernel.Refinement<InstallationFilesystemIdentity, RecoveryRemovalFailure> {
    val slot = RecoverySlot.observe(root)
    if (slot.layout != RecoveryLayout.DIRECT || !slot.validRoot())
        return io.github.amichne.kast.kernel.Refinement.Rejected(RecoveryRemovalFailure.ROOT_REJECTED)
    val receipt =
        when (val decoded = readRecoveryDocument(slot)) {
            is RecoveryDocumentRead.Decoded -> decoded.receipt
            RecoveryDocumentRead.Rejected ->
                return io.github.amichne.kast.kernel.Refinement.Rejected(RecoveryRemovalFailure.RECEIPT_REJECTED)
        }
    if (!matchesInstallation(receipt, root))
        return io.github.amichne.kast.kernel.Refinement.Rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    return when (receipt.stage) {
        RecoveryStage.PREPARED,
        RecoveryStage.ACTIVE ->
            if (validRecoveryPlugin(receipt.plugin, receipt.pluginRoot))
                io.github.amichne.kast.kernel.Refinement.Refined(receipt.installationIdentity)
            else io.github.amichne.kast.kernel.Refinement.Rejected(RecoveryRemovalFailure.RECEIPT_REJECTED)
        RecoveryStage.PLUGIN_PREPARED,
        RecoveryStage.FINALIZING ->
            io.github.amichne.kast.kernel.Refinement.Rejected(RecoveryRemovalFailure.RECOVERY_PENDING)
        RecoveryStage.DETACHED,
        RecoveryStage.CLEAN ->
            io.github.amichne.kast.kernel.Refinement.Rejected(RecoveryRemovalFailure.RECOVERY_UNRESOLVED)
    }
}

/** Called under the stable activation lock after the physical payload has been committed. */
fun prepareInstallationRecovery(
    root: Path,
    baseline: InstallationRecoveryBaseline? = null,
): InstallationRecoveryPreparation {
    if (baseline != null) return baseline.prepare(root)
    val bundle = root.parent.resolve("recovery/installation")
    if (!Files.notExists(bundle, LinkOption.NOFOLLOW_LINKS)) {
        return when (admitInstallationRecovery(root)) {
            is InstallationRecoveryAdmission.Admitted -> InstallationRecoveryPreparation.Prepared
            is InstallationRecoveryAdmission.Pending,
            InstallationRecoveryAdmission.Rejected -> InstallationRecoveryPreparation.Rejected
        }
    }
    return prepareRecovery(root, null, null)
}

private fun prepareRecovery(root: Path, plugin: RecoveryPlugin?, pluginRoot: String?): InstallationRecoveryPreparation {
    return try {
        if (root.fileName.toString() != "installation" || !physicalDirectory(root))
            return InstallationRecoveryPreparation.Rejected
        val recovery = root.parent.resolve("recovery")
        val bundle = recovery.resolve("installation")
        listOf(recovery, bundle).forEach(::prepareDirectory)
        for (name in listOf("installation-recovery.py", "installation-lifecycle.py")) {
            val source = root.resolve("share/kast/$name")
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return InstallationRecoveryPreparation.Rejected
            Files.copy(source, bundle.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            Files.setPosixFilePermissions(bundle.resolve(name), PosixFilePermissions.fromString("rw-------"))
            force(bundle.resolve(name))
        }
        writeReceipt(
            bundle.resolve("receipt.json"),
            RecoveryReceipt(
                installation = root.toString(),
                installationIdentity = identity(root),
                plugin = plugin,
                pluginRoot = pluginRoot,
            ),
        )
        InstallationRecoveryPreparation.Prepared
    } catch (_: java.io.IOException) {
        InstallationRecoveryPreparation.Rejected
    } catch (_: SecurityException) {
        InstallationRecoveryPreparation.Rejected
    }
}

private fun readReceipt(path: Path): String {
    if (!validReceiptFile(path) || !validReceiptOwner(path)) throw java.io.IOException("recovery receipt rejected")
    val before = identity(path)
    return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)
        .use { it.readNBytes(MAXIMUM_RECOVERY_RECEIPT_BYTES + 1) }
        .also {
            if (it.size > MAXIMUM_RECOVERY_RECEIPT_BYTES || identity(path) != before)
                throw java.io.IOException("recovery receipt rejected")
        }
        .let { bytes ->
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }
}

private fun validReceiptFile(path: Path): Boolean =
    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
        path.toRealPath() == path &&
        Files.size(path) <= MAXIMUM_RECOVERY_RECEIPT_BYTES

private fun validReceiptOwner(path: Path): Boolean =
    physicalDirectory(path.parent) &&
        identity(path).owner == identity(path.parent).owner &&
        Files.getPosixFilePermissions(path).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }

private fun physicalDirectory(path: Path): Boolean =
    path.isAbsolute &&
        path.normalize() == path &&
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
        path.toRealPath() == path

private fun prepareDirectory(path: Path) {
    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
    if (!physicalDirectory(path)) throw java.io.IOException("recovery directory rejected")
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
}

private fun writeReceipt(path: Path, document: RecoveryReceipt) {
    val temporary = Files.createTempFile(path.parent, ".receipt-", ".tmp")
    try {
        Files.writeString(temporary, recoveryJson.encodeToString(RecoveryReceipt.serializer(), document))
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
        force(temporary)
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun force(path: Path) = FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }

private fun identity(path: Path) =
    InstallationFilesystemIdentity(
        (Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
    )

private val recoveryJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
