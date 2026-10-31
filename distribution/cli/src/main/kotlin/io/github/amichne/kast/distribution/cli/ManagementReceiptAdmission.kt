package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.ManagementBuildBinding
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.nio.file.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val receiptVersionJson = Json { ignoreUnknownKeys = true }
private const val MANAGEMENT_RECEIPT_BYTES = 65536L

@Serializable
internal enum class ReleaseChannel {
    STABLE,
    DEVELOPER,
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class ManagedRegistration(
    val connection: HarnessConnection,
    val destination: String,
    val payloadSha256: String,
    // Receipts predating destination selection lack this routing proof.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val directory: String? = null,
)

@Serializable private data class ReceiptVersion(val schemaVersion: Int)

@Serializable
private data class LegacyManagedRegistration(val harness: Harness, val destination: String, val payloadSha256: String)

@Serializable
private data class LegacyManagementReceipt(
    val schemaVersion: Int,
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val channel: ReleaseChannel,
    val registrations: List<LegacyManagedRegistration>,
) {
    fun refine(): ManagementReceipt =
        ManagementReceipt(
            2,
            installationRoot,
            executable,
            executableSha256,
            channel,
            registrations.map { registration ->
                val connection =
                    when (registration.harness) {
                        Harness.CODEX -> HarnessConnection.CODEX_MCP
                        Harness.COPILOT -> HarnessConnection.COPILOT
                        Harness.PI -> HarnessConnection.PI
                    }
                ManagedRegistration(connection, registration.destination, registration.payloadSha256)
            },
        )
}

@Serializable
internal data class ManagementReceipt(
    val schemaVersion: Int,
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val channel: ReleaseChannel,
    val registrations: List<ManagedRegistration>,
    val updatePolicy: UpdatePolicy =
        if (channel == ReleaseChannel.STABLE) UpdatePolicy.TrackStable else UpdatePolicy.TrackMain,
    val installedBuild: InstalledBuildIdentity? = null,
    val guidance: List<ManagedGuidance> = emptyList(),
)

internal sealed interface ReceiptRead {
    data class Read(val receipt: ManagementReceipt) : ReceiptRead

    data class Unavailable(val reason: String) : ReceiptRead
}

internal fun receiptPath(root: Path): Path = root.resolve("management.json")

private fun decodeReceipt(raw: String): ReceiptRead {
    val receipt =
        try {
            when (receiptVersionJson.decodeFromString<ReceiptVersion>(raw).schemaVersion) {
                1 -> managementJson.decodeFromString<LegacyManagementReceipt>(raw).refine()
                2 -> managementJson.decodeFromString<ManagementReceipt>(raw)
                else -> return ReceiptRead.Unavailable("receipt_invalid")
            }
        } catch (_: SerializationException) {
            return ReceiptRead.Unavailable("receipt_invalid")
        } catch (_: IllegalArgumentException) {
            return ReceiptRead.Unavailable("receipt_invalid")
        }
    return ReceiptRead.Read(receipt)
}

internal fun readReceipt(root: Path): ReceiptRead =
    admitManagementReceipt(root, readBoundedFile(receiptPath(root), MANAGEMENT_RECEIPT_BYTES))

internal fun admitManagementReceipt(root: Path, raw: String?): ReceiptRead {
    if (raw == null) return ReceiptRead.Unavailable("receipt_unavailable")
    val receipt =
        when (val decoded = decodeReceipt(raw)) {
            is ReceiptRead.Read -> decoded.receipt
            is ReceiptRead.Unavailable -> return decoded
        }
    if (!validReceiptIdentity(root, receipt) || !validRecordedBuild(receipt) || !validGuidance(receipt.guidance))
        return ReceiptRead.Unavailable("receipt_invalid")
    return ReceiptRead.Read(receipt)
}

private fun validManagedRegistration(root: Path, registration: ManagedRegistration): Boolean {
    if (!validSha256(registration.payloadSha256)) return false
    // Legacy receipts are refined at connect; never manufacture their missing routing proof.
    val rawDirectory = registration.directory ?: return true
    val directory =
        when (val admitted = ConnectionDirectory.admit(rawDirectory)) {
            is ConnectionDirectoryAdmission.Admitted -> admitted.directory
            is ConnectionDirectoryAdmission.Rejected -> return false
        }
    val destination =
        when (val admitted = ConnectionDirectory.admit(registration.destination)) {
            is ConnectionDirectoryAdmission.Admitted -> admitted.directory.path
            is ConnectionDirectoryAdmission.Rejected -> return false
        }
    return registrationDestinationFor(root, registration.connection, directory) == destination
}

private const val SHA256_HEX_LENGTH = 64

internal fun validSha256(raw: String): Boolean =
    raw.length == SHA256_HEX_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' }

private fun validRecordedBuild(receipt: ManagementReceipt): Boolean {
    val identity = receipt.installedBuild ?: return receipt.updatePolicy !is UpdatePolicy.PinnedBuild
    return try {
        ManagementBuildBinding(identity, receipt.updatePolicy)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun validGuidance(entries: List<ManagedGuidance>): Boolean =
    entries.map { it.harness }.distinct().size == entries.size && entries.all(::validGuidanceEntry)

private fun validGuidanceEntry(entry: ManagedGuidance): Boolean =
    normalizedAbsolutePath(entry.destination) && entry.files.isNotEmpty() && validGuidanceFiles(entry.files)

private fun validGuidanceFiles(files: List<GuidanceFile>): Boolean =
    files.map { it.path }.distinct().size == files.size &&
        files.all { validRelativeGuidancePath(it.path) && validSha256(it.sha256) }

private fun validRelativeGuidancePath(raw: String): Boolean =
    raw.isNotEmpty() && validGuidanceCharacters(raw) && raw.split('/').all(::validGuidanceSegment)

private fun validGuidanceCharacters(raw: String): Boolean = '\\' !in raw && raw.none { it.isISOControl() }

private fun validGuidanceSegment(segment: String): Boolean = segment.isNotEmpty() && segment != "." && segment != ".."

private fun normalizedAbsolutePath(raw: String): Boolean =
    try {
        val path = Path.of(raw)
        path.isAbsolute && path.normalize() == path
    } catch (_: IllegalArgumentException) {
        false
    }

private fun validReceiptIdentity(root: Path, receipt: ManagementReceipt): Boolean =
    receipt.schemaVersion == 2 && receipt.installationRoot == root.toString() && validReceiptResources(root, receipt)

private fun validReceiptResources(root: Path, receipt: ManagementReceipt): Boolean =
    validSha256(receipt.executableSha256) &&
        normalizedAbsolutePath(receipt.executable) &&
        validRegistrations(root, receipt.registrations)

private fun validRegistrations(root: Path, registrations: List<ManagedRegistration>): Boolean =
    registrations.map { it.connection }.distinct().size == registrations.size &&
        registrations.all { validManagedRegistration(root, it) }
