package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class ReleaseChannel {
    STABLE,
    DEVELOPER,
}

@Serializable
internal data class ManagedRegistration(
    val harness: Harness,
    val destination: String,
    val payloadSha256: String,
)

@Serializable
internal data class ManagementReceipt(
    val schemaVersion: Int,
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val channel: ReleaseChannel,
    val registrations: List<ManagedRegistration>,
)

@Serializable
internal enum class ObservationState {
    VERIFIED,
    UNAVAILABLE,
}

@Serializable
internal data class Observation<T>(
    val state: ObservationState,
    val value: T? = null,
    val reason: String? = null,
) {
    companion object {
        fun <T> verified(value: T): Observation<T> = Observation(ObservationState.VERIFIED, value)

        fun <T> unavailable(reason: String): Observation<T> = Observation(ObservationState.UNAVAILABLE, reason = reason)
    }
}

@Serializable
internal data class InstallationStatus(
    val commandPath: String,
    val resolvedInstallationPath: Observation<String>,
    val installedVersion: Observation<String>,
    val loadedVersion: Observation<String>,
    val registrations: Observation<List<ManagedRegistration>>,
    val activeWorkspaces: Observation<List<String>>,
    val liveConnections: Observation<Int>,
    val oneShotRequestsInFlight: Observation<Int>,
)

@Serializable
private data class InstalledManifest(
    val schemaVersion: Int,
    val semanticVersion: String,
    val installationRoot: String,
)

internal val managementJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
private val manifestJson = Json { ignoreUnknownKeys = true }

internal sealed interface ReceiptRead {
    data class Read(val receipt: ManagementReceipt) : ReceiptRead

    data class Unavailable(val reason: String) : ReceiptRead
}

internal fun receiptPath(root: Path): Path = root.resolve("management.json")

@Suppress("ComplexCondition")
internal fun readReceipt(root: Path): ReceiptRead {
    val raw = readBoundedFile(receiptPath(root), 65536) ?: return ReceiptRead.Unavailable("receipt_unavailable")
    val receipt =
        try {
            managementJson.decodeFromString<ManagementReceipt>(raw)
        } catch (_: SerializationException) {
            return ReceiptRead.Unavailable("receipt_invalid")
        } catch (_: IllegalArgumentException) {
            return ReceiptRead.Unavailable("receipt_invalid")
        }
    if (
        receipt.schemaVersion != 1 ||
            receipt.installationRoot != root.toString() ||
            receipt.registrations.map { it.harness }.toSet().size != receipt.registrations.size ||
            !validSha256(receipt.executableSha256) ||
            receipt.registrations.any { !validSha256(it.payloadSha256) }
    )
        return ReceiptRead.Unavailable("receipt_invalid")
    val executable =
        try {
            Path.of(receipt.executable)
        } catch (_: IllegalArgumentException) {
            return ReceiptRead.Unavailable("receipt_invalid")
        }
    if (!executable.isAbsolute || executable.normalize() != executable)
        return ReceiptRead.Unavailable("receipt_invalid")
    return ReceiptRead.Read(receipt)
}

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
internal fun readStatus(root: Path, commandPath: String): InstallationStatus {
    val receipt = readReceipt(root)
    val installation = resolvedInstallation(root, receipt)
    val installedVersion = installation?.let { selected ->
        readBoundedFile(selected.resolve("installation.json"), 67_108_864)
            ?.let { raw ->
                try {
                    manifestJson.decodeFromString<InstalledManifest>(raw)
                } catch (_: SerializationException) {
                    null
                }
            }
            ?.takeIf {
                it.schemaVersion == 2 &&
                    it.installationRoot == selected.toString() &&
                    Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(it.semanticVersion)
            }
            ?.semanticVersion
    }
    val runtime = installation?.let(::observeRuntime)
    val noRuntimeProjection = "runtime_projection_unavailable"
    return InstallationStatus(
        commandPath = commandPath,
        resolvedInstallationPath =
            installation?.let { Observation.verified(it.toString()) }
                ?: Observation.unavailable("installation_unavailable"),
        installedVersion =
            installedVersion?.let { Observation.verified(it) }
                ?: Observation.unavailable("installed_version_unavailable"),
        loadedVersion = runtime?.loadedVersion ?: Observation.unavailable(noRuntimeProjection),
        registrations =
            when (receipt) {
                is ReceiptRead.Read -> Observation.verified(receipt.receipt.registrations)
                is ReceiptRead.Unavailable -> Observation.unavailable(receipt.reason)
            },
        activeWorkspaces = runtime?.activeWorkspaces ?: Observation.unavailable(noRuntimeProjection),
        liveConnections = runtime?.liveConnections ?: Observation.unavailable(noRuntimeProjection),
        oneShotRequestsInFlight =
            installation?.let(::observeOneShotRequests) ?: Observation.unavailable(noRuntimeProjection),
    )
}

private fun resolvedInstallation(root: Path, receipt: ReceiptRead): Path? {
    if (receipt !is ReceiptRead.Read) return null
    val pair =
        try {
            root.resolve("current").toRealPath() to root.toRealPath().resolve("versions")
        } catch (_: Exception) {
            return null
        }
    return pair.first.takeIf { it.parent == pair.second }
}

internal fun readBoundedFile(path: Path, maximumBytes: Long): String? =
    try {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maximumBytes) null
        else
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val bytes = input.readNBytes((maximumBytes + 1).toInt())
                bytes.takeIf { it.size <= maximumBytes }?.decodeToString()
            }
    } catch (_: Exception) {
        null
    }

private const val SHA256_HEX_LENGTH = 64

internal fun validSha256(raw: String): Boolean =
    raw.length == SHA256_HEX_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' }

internal fun InstallationStatus.asJson(): String = managementJson.encodeToString(this)
