package io.github.amichne.kast.cli

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Serializable
internal data class InstallManifest(
    val version: String = "",
    val installedAt: String = "",
    val platform: String = "",
    val components: List<String> = emptyList(),
    val managedPaths: List<String> = emptyList(),
    val shellRcPatches: List<ShellRcPatch> = emptyList(),
    val repos: List<ManagedRepo> = emptyList(),
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
internal data class ShellRcPatch(
    val file: String,
    val marker: String,
)

@Serializable
internal data class ManagedRepo(
    val path: String,
    val copilotExtensionVersion: String,
)

internal class InstallManifestStore(
    private val installRootProvider: () -> Path = { Path.of(System.getProperty("user.home")).resolve(".kast") },
    private val clock: () -> Instant = Instant::now,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun installRoot(): Path = installRootProvider().toAbsolutePath().normalize()

    fun manifestPath(): Path = installRoot().resolve(".manifest.json")

    fun read(): InstallManifest? {
        val path = manifestPath()
        if (!Files.isRegularFile(path)) {
            return null
        }
        return runCatching {
            json.decodeFromString<InstallManifest>(Files.readString(path))
        }.getOrNull()
    }

    fun write(manifest: InstallManifest) {
        val path = manifestPath()
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, json.encodeToString(manifest) + System.lineSeparator())
    }

    fun update(transform: (InstallManifest?) -> InstallManifest): InstallManifest {
        val updated = transform(read())
        write(updated)
        return updated
    }

    fun recordRepo(repoRoot: Path, version: String): InstallManifest {
        val normalizedPath = repoRoot.toAbsolutePath().normalize().toString()
        return update { existing ->
            val baseline = existing ?: InstallManifest(
                version = version,
                installedAt = clock().toString(),
            )
            baseline.copy(
                version = baseline.version.ifBlank { version },
                installedAt = baseline.installedAt.ifBlank { clock().toString() },
                repos = (baseline.repos.filterNot { it.path == normalizedPath } + ManagedRepo(
                    path = normalizedPath,
                    copilotExtensionVersion = version,
                )).sortedBy(ManagedRepo::path),
            )
        }
    }
}
