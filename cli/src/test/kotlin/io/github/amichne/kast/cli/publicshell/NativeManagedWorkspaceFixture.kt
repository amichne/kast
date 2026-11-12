package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.PayloadFile
import io.github.amichne.kast.distribution.cli.ManagedWorkspaceQuery
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.ManagedHeadlessAssemblyRecord
import io.github.amichne.kast.distribution.managed.ManagedHeadlessInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Inert vendor files plus production workspace admission; the peer supplies transport observations only. */
internal class NativeManagedWorkspaceFixture(fixture: NativePublicFixture) {
    val deployment: ManagedHeadlessInstallation

    init {
        val json = Json { encodeDefaults = true }
        val record = NativeProductRecord()
        val mac = System.getProperty("os.name").startsWith("Mac")
        val target = record.targets.single { it.target == if (mac) "macos-aarch64" else "linux-x86_64" }
        val store = fixture.installation.parent.resolveSibling("kast-workspaces")
        Files.createDirectories(
            store,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        val runtime = Files.createDirectories(store.resolve("runtimes/${target.runtime.sha256}"))
        val host = if (mac) "host/Kast.app/Contents" else "host"
        val idea = runtime.resolve(host)
        val files = runtimeFiles(runtime, host, mac)
        val bootstrap = idea.resolve("third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar")
        Files.writeString(
            runtime.resolve("manifest.json"),
            json.encodeToString(NativeRuntimeManifest(target.target, nativeDigest(bootstrap), files)),
        )
        Files.writeString(
            runtime.resolve("runtime.json"),
            json.encodeToString(
                NativeRuntimeReceipt(target.runtime.sha256, nativeDigest(runtime.resolve("manifest.json")))
            ),
        )
        Files.writeString(
            idea.resolve("kast-headless.json"),
            json.encodeToString(
                ManagedHeadlessAssemblyRecord(
                    1,
                    idea.toString(),
                    "a".repeat(64),
                    nativeDigest(bootstrap),
                    "262.10315.125",
                    "262.10315.125-IJ",
                )
            ),
        )
        val recordPath = fixture.installation.resolve("share/kast/build-record.json")
        val selectionPath = fixture.installation.resolve("share/kast/product-selection.json")
        Files.writeString(recordPath, json.encodeToString(record))
        Files.writeString(
            selectionPath,
            json.encodeToString(
                NativeProductSelection(
                    buildRecordSha256 = nativeDigest(recordPath),
                    target = target.target,
                    runtimeRoot = runtime.toString(),
                    runtimeManifestSha256 = nativeDigest(runtime.resolve("manifest.json")),
                )
            ),
        )
        val manifestPath = fixture.installation.resolve("installation.json")
        val manifest = json.decodeFromString<InstallationManifest>(Files.readString(manifestPath))
        Files.writeString(
            manifestPath,
            json.encodeToString(
                manifest.copy(
                    payloadFiles =
                        manifest.payloadFiles +
                            listOf(recordPath, selectionPath).map {
                                Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-r--r--"))
                                PayloadFile(
                                    fixture.installation.relativize(it).toString(),
                                    "sha256:${nativeDigest(it)}",
                                    420,
                                )
                            }
                )
            ),
        )
        val root =
            (FilesystemCanonicalRootDiscovery.discover(fixture.workspace) as CanonicalRootDiscovery.Discovered).root
        val admitted = ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap())
        check(admitted is Refinement.Refined) { "Managed fixture admission failed: $admitted" }
        admitted.value.use { query ->
            val launch = SelectedIdeInstallation.resolve(idea)
            check(launch is SelectedIdeLaunch.Resolved) { launch }
            val selected =
                ManagedHeadlessInstallation.resolve(
                    launch,
                    Path.of(query.environment.getValue("KAST_MANAGED_HEADLESS_STATE")),
                    query.home,
                )
            check(selected is Refinement.Refined) { selected }
            deployment = selected.value
        }
    }

    private fun runtimeFiles(runtime: Path, host: String, mac: Boolean): List<NativeRuntimeFile> {
        val json = Json { encodeDefaults = true }
        val productDirectory = if (mac) "$host/Resources" else host
        val launcher = if (mac) "$host/MacOS/idea" else "$host/bin/idea"
        val javaHome = if (mac) "$host/jbr/Contents/Home" else "$host/jbr"
        return mapOf(
                "$productDirectory/product-info.json" to json.encodeToString(vendorProduct(mac)),
                launcher to "must never execute",
                "$javaHome/bin/java" to "must never execute",
                "$host/bin/idea.vmoptions" to "-Dvendor.required=true\n",
                "$host/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar" to "bootstrap",
                "components/plugins/kast-ide-hosted/lib/kast-ide-hosted.jar" to "inert Host plugin",
            )
            .map { (relative, content) ->
                val file = runtime.resolve(relative)
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
                val executable = relative.endsWith("/idea") || relative.endsWith("/java")
                if (executable) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
                NativeRuntimeFile(relative, Files.size(file), nativeDigest(file), executable)
            }
    }
}

private fun vendorProduct(mac: Boolean): NativeVendorProduct =
    NativeVendorProduct(
        launch =
            listOf(
                NativeVendorLaunch(
                    if (mac) "macOS" else "Linux",
                    if (mac) "aarch64" else "amd64",
                    if (mac) "../MacOS/idea" else "bin/idea",
                )
            )
    )

@Serializable
private data class NativeProductSelection(
    val buildRecordSha256: String,
    val target: String,
    val runtimeRoot: String,
    val runtimeManifestSha256: String,
    val schemaVersion: Int = 1,
    val releaseTag: String = "developer-v0.0.1",
    val publicVersion: String? = null,
    val version: String = "0.0.1",
    val sourceRevision: String = "a".repeat(40),
    val sourceTree: String = "b".repeat(40),
    val bundleSha256: String = "c".repeat(64),
    val qualificationSha256: String = "e".repeat(64),
    val runtimeArchiveSha256: String = "d".repeat(64),
    val policy: String = "TrackMain",
)

@Serializable
private data class NativeRuntimeManifest(
    val platform: String,
    val bootstrapSha256: String,
    val files: List<NativeRuntimeFile>,
    val schemaVersion: Int = 1,
    val kind: String = "KAST_RUNTIME_V1",
    val version: String = "0.0.1",
    val platformBuild: String = "262.10315.125",
    val distributionSha256: String = "a".repeat(64),
)

@Serializable
private data class NativeRuntimeFile(
    val path: String,
    val bytes: Long,
    val sha256: String,
    val executable: Boolean,
    val type: String = "FILE",
)

@Serializable
private data class NativeRuntimeReceipt(
    val archiveSha256: String,
    val manifestSha256: String,
    val schemaVersion: Int = 1,
)

@Serializable
private data class NativeVendorProduct(
    val launch: List<NativeVendorLaunch>,
    val buildNumber: String = "262.10315.125",
    val dataDirectoryName: String = "KastNativeTest",
)

@Serializable private data class NativeVendorLaunch(val os: String, val arch: String, val launcherPath: String)
