package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ManagementBuildBinding
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

internal sealed interface ManagementPublication {
    data object Preserve : ManagementPublication

    class Selected private constructor(val binding: ManagementBuildBinding) : ManagementPublication {
        companion object {
            fun admit(root: Path, file: Path): Selected {
                val binding = readPublicationBinding(file)
                val installed = readPublicationIdentity(selectedInstallation(root))
                if (
                    installed.version != binding.identity.version ||
                        installed.sourceRevision != binding.identity.buildRevision ||
                        installed.sourceTree != binding.identity.sourceTree
                )
                    throw ManagementRejected("managed-publication", "installed selection mismatch")
                return Selected(binding)
            }
        }
    }
}

@Serializable
internal data class ControlBuildIdentity(val version: String, val sourceRevision: String, val sourceTree: String)

private const val PUBLICATION_SELECTION_BYTES = 65_536L

/** Parse the private binding path before any publication effect. */
internal fun selectedManagementPublication(
    root: Path,
    environment: Map<String, String>,
): ManagementPublication.Selected {
    val raw =
        environment["KAST_MANAGEMENT_SELECTION"]
            ?: throw ManagementRejected("managed-publication", "selection unavailable")
    val path =
        try {
            Path.of(raw)
        } catch (_: InvalidPathException) {
            throw ManagementRejected("managed-publication", "selection invalid")
        }
    if (!path.isAbsolute || path.normalize() != path)
        throw ManagementRejected("managed-publication", "selection invalid")
    return ManagementPublication.Selected.admit(root, path)
}

private const val PUBLICATION_IDENTITY_BYTES = 4096L

private fun readPublicationBinding(file: Path): ManagementBuildBinding {
    val raw =
        readBoundedFile(file, PUBLICATION_SELECTION_BYTES)
            ?: throw ManagementRejected("managed-publication", "selection unavailable")
    val decoded =
        try {
            managementJson.decodeFromString<ManagementBuildBinding>(raw)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    return decoded ?: throw ManagementRejected("managed-publication", "selection invalid")
}

private fun readPublicationIdentity(installation: Path): ControlBuildIdentity {
    val manifest = readBundledManifest(installation)
    val relative = "share/kast/build-identity.json"
    if (manifest !is BundledManifestRead.Read || !payloadOwned(installation, manifest.manifest, relative))
        throw ManagementRejected("managed-publication", "installed identity invalid")
    return decodePublicationIdentity(installation.resolve(relative))
}

private fun decodePublicationIdentity(file: Path): ControlBuildIdentity {
    val raw =
        readBoundedFile(file, PUBLICATION_IDENTITY_BYTES)
            ?: throw ManagementRejected("managed-publication", "installed identity unavailable")
    return try {
        managementJson.decodeFromString<ControlBuildIdentity>(raw)
    } catch (_: SerializationException) {
        throw ManagementRejected("managed-publication", "installed identity invalid")
    }
}
