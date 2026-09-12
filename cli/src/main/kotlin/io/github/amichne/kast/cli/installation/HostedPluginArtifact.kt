package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Read-only admission of the exact build-owned descriptor and its already checksum-verified plugin archive. */
internal fun admitHostedPluginArtifact(
    request: InstallationRequest
): Refinement<HostedPluginManifest, InstallationFailure> {
    val path = request.controlRoot.value.resolve("share/kast/ide-host.json")
    return try {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            return rejectedArtifact(InstallationFailure.CONTROL_REJECTED)
        val bytes =
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use {
                it.readNBytes(MAXIMUM_MANIFEST_BYTES.toInt() + 1)
            }
        if (bytes.size > MAXIMUM_MANIFEST_BYTES) return rejectedArtifact(InstallationFailure.CONTROL_REJECTED)
        val manifest = Json.decodeFromString<HostedPluginManifest>(bytes.decodeToString(throwOnInvalidSequence = true))
        if (!manifest.matches(request)) return rejectedArtifact(InstallationFailure.PLUGIN_REJECTED)
        if (!verifyPluginArchive(request.pluginArchive.value))
            return rejectedArtifact(InstallationFailure.PLUGIN_REJECTED)
        Refinement.Refined(manifest)
    } catch (_: SerializationException) {
        rejectedArtifact(InstallationFailure.CONTROL_REJECTED)
    } catch (_: IOException) {
        rejectedArtifact(InstallationFailure.PLUGIN_REJECTED)
    } catch (_: IllegalArgumentException) {
        rejectedArtifact(InstallationFailure.PLUGIN_REJECTED)
    }
}

private fun rejectedArtifact(failure: InstallationFailure) = Refinement.Rejected(failure)

@Serializable
internal data class HostedPluginManifest(
    val schemaVersion: Int,
    val productVersion: String,
    val execution: String,
    val ideaBuild: String,
    val kotlinPluginBuild: String,
    val fileName: String,
    val sha256: String,
    val bytes: Long,
) {
    fun matches(request: InstallationRequest): Boolean =
        productMatches(request) && archiveMatches(request) && hostMatches()

    private fun productMatches(request: InstallationRequest) =
        schemaVersion == 1 && execution == "existing_ide" && productVersion == request.version.toString()

    private fun archiveMatches(request: InstallationRequest) =
        fileName == request.pluginArchive.value.fileName.toString() &&
            sha256 == "sha256:${request.pluginDigest.value}" &&
            bytes == Files.size(request.pluginArchive.value)

    private fun hostMatches() = ideaBuild.matches(Regex("[0-9]+(\\.[0-9]+)+")) && kotlinPluginBuild == "$ideaBuild-IJ"
}

private fun verifyPluginArchive(path: Path): Boolean = ZipFile(path.toFile()).use(::validPluginEntries)

private fun validPluginEntries(zip: ZipFile): Boolean {
    val names = mutableSetOf<String>()
    for (entry in zip.entries().asSequence()) {
        val name = entry.name
        if (!canonicalArchiveMember(name) || !pluginMember(name)) return false
        if (names.size >= MAXIMUM_CONTROL_FILES || !names.add(name.trimEnd('/'))) return false
    }
    return names.any { it.startsWith("kast-ide-hosted/lib/") && it.endsWith(".jar") }
}

private fun canonicalArchiveMember(name: String): Boolean {
    if (name.isBlank()) return false
    val path = Path.of(name)
    return !path.isAbsolute && path.normalize().toString() == name.trimEnd('/') && path.none { it.toString() == ".." }
}

private fun pluginMember(name: String): Boolean =
    name.startsWith("kast-ide-hosted/") &&
        !name.endsWith("/product-info.json") &&
        listOf("/idea-home/", "/plugins/Kotlin/", "/plugins/gradle/").none(name::contains)
