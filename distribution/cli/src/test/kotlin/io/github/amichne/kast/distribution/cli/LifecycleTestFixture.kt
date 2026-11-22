package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal data class LifecycleFixture(
    val root: Path,
    val installation: Path,
    val home: Path,
    val platform: ContainerPlatform,
)

internal fun LifecycleFixture.admitLifecycle(execution: LifecycleExecution): InstallationLifecycle =
    when (val admitted = InstallationLifecycle.admitProduct(root, home, emptyMap(), platform, execution)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error("lifecycle fixture was rejected: ${admitted.failure}")
    }

internal fun closedHostProcesses() =
    object : LifecycleProcesses {
        override fun observe(pid: Long): LifecycleProcessObservation = error("unexpected process inspection")

        override fun retire(
            process: OwnedLifecycleProcess,
            operation: LifecycleOperation,
            allowance: Duration,
        ): LifecycleEffect = error("unexpected signal")

        override fun selectedHost(executable: Path): LifecycleEffect = LifecycleEffect.Completed
    }

internal fun createLifecycleFixture(temporary: Path): LifecycleFixture {
    val product = productFixture(temporary)
    val installation = product.installation
    val root = installation.parent
    val home = temporary.toRealPath().resolve("home")
    Files.createDirectories(home)
    val owned =
        listOf(
                "share/kast/libexec/kast-service",
                "share/kast/libexec/kast-management",
                "share/kast/one-shot-observation-v1",
                "lib/kast.jar",
                "share/kast/lifecycle-fence-v1",
                "share/kast/reset-fence-v1",
            )
            .map { relative ->
                val path = installation.resolve(relative)
                Files.createDirectories(path.parent)
                Files.writeString(path, if (relative.endsWith("-v1")) "1\n" else "fixture-payload")
                path.toFile().setExecutable(true)
                BundledPayload(relative, "sha256:${sha256(path)}", 493)
            }
    val selection =
        listOf("share/kast/build-record.json", "share/kast/product-selection.json").map { relative ->
            BundledPayload(relative, "sha256:${sha256(installation.resolve(relative))}", 420)
        }
    Files.writeString(
        installation.resolve("installation.json"),
        managementJson.encodeToString(FixtureLifecycleManifest(3, "1.2.3", installation.toString(), owned + selection)),
    )
    val config = installation.resolve("config")
    Files.createDirectories(config)
    Files.writeString(config.resolve("environment"), "owned-config")
    createSelectedHost(temporary, config)
    val native = temporary.toRealPath().resolve("kast-command")
    Files.writeString(native, "native-fixture")
    writeTestManagementReceipt(root, native, product.reference)
    return LifecycleFixture(root, installation, home, product.runtime.manifest.platform)
}

private fun createSelectedHost(temporary: Path, config: Path) {
    val bundle = temporary.toRealPath().resolve("IDE.app")
    val ideHome = bundle.resolve("Contents")
    val executable = ideHome.resolve("MacOS/idea")
    Files.createDirectories(executable.parent)
    Files.writeString(executable, "fixture-IDE")
    executable.toFile().setExecutable(true)
    Files.createDirectories(ideHome.resolve("Resources"))
    Files.writeString(
        ideHome.resolve("Resources/product-info.json"),
        managementJson.encodeToString(
            FixtureProduct("262.1", listOf(FixtureLaunch("macOS", "aarch64", "../MacOS/idea")))
        ),
    )
    Files.writeString(
        config.resolve("selected-ide.json"),
        managementJson.encodeToString(
            FixtureHost("resolved", ideHome.toString(), bundle.toString(), executable.toString())
        ),
    )
}

@Serializable
internal data class FixtureHost(val type: String, val home: String, val bundle: String, val executable: String)

@Serializable internal data class FixtureProduct(val buildNumber: String, val launch: List<FixtureLaunch>)

@Serializable internal data class FixtureLaunch(val os: String, val arch: String, val launcherPath: String)

@Serializable
internal data class FixtureLifecycleManifest(
    val schemaVersion: Int,
    val semanticVersion: String,
    val installationRoot: String,
    val payloadFiles: List<BundledPayload>,
)

@Serializable
internal data class FixtureInstallerReport(
    val operation: String = "installation.install",
    val status: String = "installed-activation-pending",
    val activation: FixtureActivation = FixtureActivation(),
    val semanticVersion: String = "1.2.3",
)

@Serializable internal data class FixtureActivation(val type: String = "pending", val reason: String = "shutdown fence")
