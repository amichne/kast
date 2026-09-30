package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable internal data class BundledPayload(val path: String, val sha256: String, val mode: Int)

@Serializable
internal data class BundledManifest(
    val schemaVersion: Int,
    val installationRoot: String,
    val payloadFiles: List<BundledPayload>,
)

internal sealed interface BundledManifestRead {
    data class Read(val manifest: BundledManifest) : BundledManifestRead

    data object Unavailable : BundledManifestRead

    data object Invalid : BundledManifestRead
}

private val bundledManifestJson = Json { ignoreUnknownKeys = true }

internal fun readBundledManifest(installation: Path): BundledManifestRead {
    val raw =
        readBoundedFile(
            installation.resolve("installation.json"),
            ControlDistributionLimits.maximumManifestBytes.toLong(),
        ) ?: return BundledManifestRead.Unavailable
    return try {
        BundledManifestRead.Read(bundledManifestJson.decodeFromString<BundledManifest>(raw))
    } catch (_: SerializationException) {
        BundledManifestRead.Invalid
    }
}

@Suppress("ThrowsCount") // Each incomplete installation proof rejects before external commands run.
internal fun selectedInstallation(root: Path): Path {
    val payload = root.resolve("installation")
    if (
        Files.isSymbolicLink(payload) ||
            !Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(payload.resolve("installation.json"), LinkOption.NOFOLLOW_LINKS)
    )
        throw ManagementRejected("installation-admission", "installation is unavailable")
    val selected =
        try {
            payload.toRealPath()
        } catch (_: Exception) {
            throw ManagementRejected("installation-admission", "installation is unavailable")
        }
    if (selected != root.toRealPath().resolve("installation"))
        throw ManagementRejected("installation-admission", "installation is invalid")
    val manifest =
        when (val read = readBundledManifest(selected)) {
            is BundledManifestRead.Read -> read.manifest
            BundledManifestRead.Unavailable,
            BundledManifestRead.Invalid -> throw ManagementRejected("installation-admission", "manifest is unavailable")
        }
    if (
        manifest.schemaVersion != INSTALLATION_MANIFEST_SCHEMA_VERSION ||
            manifest.installationRoot != selected.toString()
    )
        throw ManagementRejected("installation-admission", "manifest is incompatible")
    return selected
}
