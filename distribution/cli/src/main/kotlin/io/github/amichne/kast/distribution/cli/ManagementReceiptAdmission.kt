package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.ManagementBuildBinding
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.nio.file.Path
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

@Serializable private data class ReceiptVersion(val schemaVersion: Int)

/** The admitted projection of a schema 3 product management receipt. Agent configuration is not Kast's. */
internal data class ManagementReceipt(
    val schemaVersion: Int,
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val channel: ReleaseChannel,
    val updatePolicy: UpdatePolicy,
    val installedBuild: InstalledBuildIdentity,
    val product: InstalledProductReference,
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
                PRODUCT_MANAGEMENT_SCHEMA_VERSION ->
                    managementJson.decodeFromString<ProductManagementReceipt>(raw).refine()
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
    if (!validReceiptIdentity(root, receipt) || !validRecordedBuild(receipt))
        return ReceiptRead.Unavailable("receipt_invalid")
    return ReceiptRead.Read(receipt)
}

private const val SHA256_HEX_LENGTH = 64

internal fun validSha256(raw: String): Boolean =
    raw.length == SHA256_HEX_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' }

private fun validRecordedBuild(receipt: ManagementReceipt): Boolean {
    val identity = receipt.installedBuild
    return try {
        ManagementBuildBinding(identity, receipt.updatePolicy)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun normalizedAbsolutePath(raw: String): Boolean =
    try {
        val path = Path.of(raw)
        path.isAbsolute && path.normalize() == path
    } catch (_: IllegalArgumentException) {
        false
    }

private fun validReceiptIdentity(root: Path, receipt: ManagementReceipt): Boolean =
    receipt.installationRoot == root.toString() &&
        validReceiptResources(receipt) &&
        receipt.schemaVersion == PRODUCT_MANAGEMENT_SCHEMA_VERSION &&
        validManagementProduct(root, receipt)

private fun validReceiptResources(receipt: ManagementReceipt): Boolean =
    validSha256(receipt.executableSha256) && normalizedAbsolutePath(receipt.executable)
