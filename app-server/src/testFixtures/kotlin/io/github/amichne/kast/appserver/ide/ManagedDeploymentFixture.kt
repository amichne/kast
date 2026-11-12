package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.managed.ManagedHeadlessAssemblyRecord
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostAutoReload
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostMode
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SHA256_HEX_LENGTH = 64
private const val GIT_REVISION_HEX_LENGTH = 40
private const val FIXTURE_PROCESS_START_MILLIS = 100L

/** Caller-owned private files; vendor executables are inert and no product process is started. */
class ManagedDeploymentFixture(root: Path) {
    val idea: Path = Files.createDirectory(root.resolve("idea"))
    val state: Path = Files.createDirectory(root.resolve("state"))
    val home: Path = Files.createDirectory(state.resolve("home"))
    internal val deployment = IdeLifecycleDeployment.Headless(state)
    val configuration: ResolvedKastConfiguration
    internal val selected: SelectedLifecycleDeployment.Headless

    init {
        for (name in listOf("system", "log", "tmp")) Files.createDirectory(state.resolve(name))
        Files.createDirectory(idea.resolve("bin"))
        Files.writeString(idea.resolve("bin/idea"), "must never execute").toFile().setExecutable(true)
        Files.writeString(idea.resolve("bin/idea.vmoptions"), "-Dvendor.required=true\n")
        Files.createDirectories(idea.resolve("jbr/bin"))
        Files.writeString(idea.resolve("jbr/bin/java"), "must never execute").toFile().setExecutable(true)
        Files.createDirectories(home.resolve(".local/share/JetBrains/IntelliJIdea2026.2/plugins"))
        val bootstrap = idea.resolve("third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar")
        Files.createDirectories(bootstrap.parent)
        Files.writeString(bootstrap, "bootstrap")
        val json = Json { encodeDefaults = true }
        Files.writeString(idea.resolve("product-info.json"), json.encodeToString(Product()))
        Files.writeString(
            idea.resolve("kast-headless.json"),
            json.encodeToString(
                ManagedHeadlessAssemblyRecord(
                    schemaVersion = 1,
                    installation = idea.toString(),
                    distributionSha256 = "a".repeat(SHA256_HEX_LENGTH),
                    bootstrapSha256 = "333c04dd151a2a6831c039cb9a651df29198be8a04e16ce861d4b6a34a11c954",
                    platformBuild = "262.10315.125",
                    kotlinPluginBuild = "262.10315.125-IJ",
                )
            ),
        )
        configuration =
            (ResolvedKastConfiguration.resolve(
                    ConfigurationSources(
                        environment =
                            mapOf(
                                "KAST_INSTALL_IDEA_HOME" to idea.toString(),
                                "KAST_MANAGED_HEADLESS_STATE" to state.toString(),
                            )
                    )
                ) as Refinement.Refined)
                .value
        selected =
            (selectLifecycleDeployment(deployment, home, idea) as Refinement.Refined).value
                as SelectedLifecycleDeployment.Headless
    }

    fun identity(host: String = "host", pid: Long = ProcessHandle.current().pid()): IdeHostRuntimeIdentity =
        IdeHostRuntimeIdentity(
            ideaHome = idea.toString(),
            profile = selected.installation.config.toString(),
            pid = pid,
            processStartEpochMillis = FIXTURE_PROCESS_START_MILLIS,
            hostLifetime = host,
            buildIdentity =
                IdeHostBuildIdentity("1.2.3", "a".repeat(GIT_REVISION_HEX_LENGTH), "b".repeat(GIT_REVISION_HEX_LENGTH)),
            autoReload = HostAutoReload.DISABLED,
            mode = IdeHostMode.MANAGED_HEADLESS,
        )

    @Serializable
    private data class Product(
        val buildNumber: String = "262.10315.125",
        val dataDirectoryName: String = "IntelliJIdea2026.2",
        val launch: List<Launch> = listOf(Launch()),
    )

    @Serializable
    private data class Launch(
        val os: String = "Linux",
        val arch: String = "amd64",
        val launcherPath: String = "bin/idea",
        val vmOptionsFilePath: String = "bin/idea.vmoptions",
    )
}

/** The namespace is derived only from the caller's uniquely allocated test HOME. */
fun cleanupHostedEndpointFixture(home: Path) {
    val endpoints = io.github.amichne.kast.distribution.contract.HostedEndpointPaths.root(home)
    if (Files.exists(endpoints)) {
        Files.walk(endpoints).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
