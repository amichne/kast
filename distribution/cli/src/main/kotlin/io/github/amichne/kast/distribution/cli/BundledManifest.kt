package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.distribution.contract.InstallationLayoutFailure
import io.github.amichne.kast.distribution.contract.ManagementRoot
import io.github.amichne.kast.kernel.Refinement
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
            InstallationLayout.of(installation).payload.manifest,
            ControlDistributionLimits.maximumManifestBytes.toLong(),
        ) ?: return BundledManifestRead.Unavailable
    return try {
        BundledManifestRead.Read(bundledManifestJson.decodeFromString<BundledManifest>(raw))
    } catch (_: SerializationException) {
        BundledManifestRead.Invalid
    }
}

/** The one management-root boundary that selects the installed version root before its manifest is admitted. */
@Suppress("ThrowsCount") // Each incomplete installation proof rejects before external commands run.
internal fun selectedInstallation(root: Path): InstallationLayout {
    val layout =
        when (val admitted = InstallationLayout.admit(ManagementRoot.of(root))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                throw ManagementRejected(
                    "installation-admission",
                    when (admitted.failure) {
                        InstallationLayoutFailure.VERSION_UNAVAILABLE -> "installation is unavailable"
                        InstallationLayoutFailure.VERSION_DISPLACED -> "installation is invalid"
                        InstallationLayoutFailure.STALE_VERSION -> "installation was replaced"
                    },
                )
        }
    if (!Files.isRegularFile(layout.payload.manifest, LinkOption.NOFOLLOW_LINKS))
        throw ManagementRejected("installation-admission", "installation is unavailable")
    val selected = layout.version.path
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
    return layout
}
