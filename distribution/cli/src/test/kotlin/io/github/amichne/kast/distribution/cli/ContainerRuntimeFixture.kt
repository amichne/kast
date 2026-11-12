package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertTrue

/** Archive admission fixture only: no executable Host or compiler evidence. */
internal fun containerRuntimeFixture(
    store: ContainerStore,
    hostPayload: String = "fixture-not-a-plugin",
    version: String = "1.2.3",
): AdmittedContainerRuntime {
    Files.createDirectories(
        store.root,
        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(containerDirectoryMode),
    )
    val platform = store.platform
    val cpu = if (platform.publicName.endsWith("aarch64")) "aarch64" else "amd64"
    val mac = platform.publicName.startsWith("macos")
    val home = platform.host
    val product =
        containerJson.encodeToString(
            Product(
                "262.10315.125",
                "KastTest2026.2",
                listOf(Launch(if (mac) "macOS" else "Linux", cpu, if (mac) "../MacOS/idea" else "bin/idea")),
            )
        )
    val payload =
        linkedMapOf(
            "$home/${if (mac) "Resources/" else ""}product-info.json" to product.toByteArray(),
            "$home/${if (mac) "MacOS/idea" else "bin/idea"}" to "fixture-not-a-host".toByteArray(),
            "$home/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar" to
                "bootstrap".toByteArray(),
            "components/plugins/kast-ide-hosted/lib/kast-ide-hosted.jar" to hostPayload.toByteArray(),
        )
    val records = payload.map { (path, bytes) ->
        RuntimeArchiveEntry.File(path, bytes.size.toLong(), digest(bytes), path.endsWith("/idea"))
    }
    val manifest = containerRuntimeManifestFixture(platform, records).copy(version = version)
    val zip = Files.createTempFile(store.root.parent, "payload-", ".zip")
    ZipOutputStream(Files.newOutputStream(zip)).use { output ->
        output.putNextEntry(ZipEntry("manifest.json"))
        output.write(containerJson.encodeToString(manifest).toByteArray())
        output.closeEntry()
        for ((path, bytes) in payload) {
            output.putNextEntry(ZipEntry(path))
            output.write(bytes)
            output.closeEntry()
        }
    }
    val imported = importContainerRuntime(zip, sha256(zip), store.runtimes, version, platform)
    assertTrue(imported is Refinement.Refined, imported.toString())
    return (imported as Refinement.Refined).value
}

internal fun containerRuntimeManifestFixture(
    platform: ContainerPlatform,
    files: List<RuntimeArchiveEntry>,
): RuntimeArchiveManifest =
    RuntimeArchiveManifest(
        "1.2.3",
        platform,
        "262.10315.125",
        "a".repeat(64),
        digest("bootstrap".toByteArray()),
        files,
    )

private fun digest(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

@Serializable
private data class Product(val buildNumber: String, val dataDirectoryName: String, val launch: List<Launch>)

@Serializable private data class Launch(val os: String, val arch: String, val launcherPath: String)
