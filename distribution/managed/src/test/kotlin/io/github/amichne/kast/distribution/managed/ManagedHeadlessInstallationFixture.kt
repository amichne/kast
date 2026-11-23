package io.github.amichne.kast.distribution.managed

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.assertInstanceOf

@Serializable
private data class Product(val buildNumber: String, val dataDirectoryName: String, val launch: List<Launch>)

@Serializable
private data class Launch(val os: String, val arch: String, val launcherPath: String, val vmOptionsFilePath: String)

internal data class ManagedHeadlessInstallationFixture(
    val root: Path,
    val state: Path,
    val launch: SelectedIdeLaunch.Resolved,
    val plugins: Path,
    val bootstrap: Path,
    val manifest: Path,
)

/** One case-owned installation and private state under the case's temporary directory. */
internal fun managedHeadlessFixture(
    directory: Path,
    build: String = "262.10315.125",
): ManagedHeadlessInstallationFixture {
    val root = directory.toRealPath()
    val installation = Files.createDirectory(root.resolve("owned idea"))
    val state = Files.createDirectory(root.resolve("state"))
    for (name in listOf("home", "config", "system", "log", "tmp")) Files.createDirectory(state.resolve(name))
    Files.createDirectory(installation.resolve("bin"))
    Files.writeString(installation.resolve("bin/idea.vmoptions"), "-Dvendor.required=true\n-Xmx2048m\n")
    Files.writeString(installation.resolve("bin/idea"), "launcher fixture").toFile().setExecutable(true)
    Files.createDirectories(installation.resolve("jbr/bin"))
    Files.writeString(installation.resolve("jbr/bin/java"), "java fixture").toFile().setExecutable(true)
    Files.writeString(
        installation.resolve("product-info.json"),
        Json.encodeToString(
            Product(
                build,
                "IntelliJIdea2026.2",
                listOf(Launch("Linux", "amd64", "bin/idea", "bin/idea.vmoptions")),
            )
        ),
    )
    val launch = assertInstanceOf<SelectedIdeLaunch.Resolved>(SelectedIdeInstallation.resolve(installation))
    val plugins = state.resolve("home/.local/share/JetBrains/IntelliJIdea2026.2/plugins")
    Files.createDirectories(plugins)
    val bootstrap = installation.resolve("third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar")
    Files.createDirectories(bootstrap.parent)
    Files.writeString(bootstrap, "bootstrap")
    val record =
        ManagedHeadlessAssemblyRecord(
            1,
            installation.toString(),
            "a".repeat(64),
            "333c04dd151a2a6831c039cb9a651df29198be8a04e16ce861d4b6a34a11c954",
            build,
            "$build-IJ",
        )
    val manifest = installation.resolve("kast-headless.json")
    Files.writeString(manifest, Json.encodeToString(record))
    return ManagedHeadlessInstallationFixture(root, state, launch, plugins, bootstrap, manifest)
}
