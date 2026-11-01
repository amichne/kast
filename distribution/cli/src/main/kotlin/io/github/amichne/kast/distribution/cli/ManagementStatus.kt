package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun printManagementStatus(root: Path, json: Boolean) {
    val commandPath =
        ProcessHandle.current()
            .info()
            .command()
            .map { Path.of(it).toAbsolutePath().normalize().toString() }
            .orElse("unavailable")
    val status = readStatus(root, commandPath)
    println(if (json) status.asJson() else status.asText())
}

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
    val hostedServices: Observation<List<HostedServiceStatus>>,
    val installedBuild: Observation<InstalledBuildIdentity> = Observation.unavailable("build_identity_unrecorded"),
    val updatePolicy: Observation<UpdatePolicy> = Observation.unavailable("update_policy_unavailable"),
    val unresolvedTransaction: Observation<PendingInstallationRecords> =
        Observation.unavailable("transaction_state_unavailable"),
    val applicationHost: Observation<LoadedApplicationHost> = Observation.unavailable("application_host_unavailable"),
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

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
internal fun readStatus(root: Path, commandPath: String): InstallationStatus {
    val receipt = readReceipt(root)
    val installation = resolvedInstallation(root, receipt)
    val installedVersion = installation?.let(::readInstalledVersion)
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
        hostedServices = runtime?.hostedServices ?: Observation.unavailable(noRuntimeProjection),
        installedBuild =
            if (receipt is ReceiptRead.Read)
                receipt.receipt.installedBuild?.let { Observation.verified(it) }
                    ?: Observation.unavailable("build_identity_unrecorded")
            else Observation.unavailable("receipt_unavailable"),
        updatePolicy =
            if (receipt is ReceiptRead.Read) Observation.verified(receipt.receipt.updatePolicy)
            else Observation.unavailable("receipt_unavailable"),
        unresolvedTransaction = passiveTransactionState(root),
        applicationHost =
            root.parent?.parent?.parent?.let { readApplicationHost(root, it) }
                ?: Observation.unavailable("user_home_unavailable"),
    )
}

private const val INSTALLED_MANIFEST_BYTES = 67_108_864L

private fun readInstalledVersion(selected: Path): String? =
    readBoundedFile(selected.resolve("installation.json"), INSTALLED_MANIFEST_BYTES)
        ?.let { raw ->
            try {
                manifestJson.decodeFromString<InstalledManifest>(raw)
            } catch (_: SerializationException) {
                null
            }
        }
        ?.takeIf {
            it.schemaVersion == INSTALLATION_MANIFEST_SCHEMA_VERSION &&
                it.installationRoot == selected.toString() &&
                Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(it.semanticVersion)
        }
        ?.semanticVersion

@Serializable
internal enum class PendingInstallationRecord {
    SHUTDOWN_FENCE,
    REPLACEMENT_RECEIPT,
    REPLACEMENT_STORAGE,
}

@Serializable internal data class PendingInstallationRecords(val records: List<PendingInstallationRecord>)

private fun passiveTransactionState(root: Path): Observation<PendingInstallationRecords> =
    Observation.verified(pendingInstallationRecords(root))

internal fun pendingInstallationRecords(root: Path): PendingInstallationRecords {
    val pending = buildList {
        if (Files.exists(root.resolve(SHUTDOWN_FENCE), LinkOption.NOFOLLOW_LINKS))
            add(PendingInstallationRecord.SHUTDOWN_FENCE)
        val replacement = root.resolve("recovery/replacement")
        if (Files.exists(replacement.resolve("receipt.json"), LinkOption.NOFOLLOW_LINKS))
            add(PendingInstallationRecord.REPLACEMENT_RECEIPT)
        else if (Files.exists(replacement, LinkOption.NOFOLLOW_LINKS))
            add(PendingInstallationRecord.REPLACEMENT_STORAGE)
    }
    return PendingInstallationRecords(pending)
}

private fun resolvedInstallation(root: Path, receipt: ReceiptRead): Path? {
    if (receipt !is ReceiptRead.Read) return null
    return try {
        selectedInstallation(root)
    } catch (_: ManagementRejected) {
        null
    }
}

internal fun readBoundedFile(path: Path, maximumBytes: Long): String? =
    try {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maximumBytes) null
        else
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val bytes = input.readNBytes((maximumBytes + 1).toInt())
                bytes.takeIf { it.size <= maximumBytes }?.decodeToString(throwOnInvalidSequence = true)
            }
    } catch (_: Exception) {
        null
    }

internal fun InstallationStatus.asJson(): String = managementJson.encodeToString(this)
