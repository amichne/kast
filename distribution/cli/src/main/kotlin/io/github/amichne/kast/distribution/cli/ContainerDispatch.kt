package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.Context
import java.io.IOException
import java.nio.file.Path

internal class ContainerNamespace : ManagementNode("container") {
    override fun help(context: Context) = "Open an isolated workspace with a self-contained IDEA runtime."

    override fun selection(): ManagementCommand? = null
}

internal sealed interface ContainerLauncher {
    class Admitted private constructor(val path: Path) : ContainerLauncher {
        companion object {
            fun fromInstallation(installation: Path, manifest: BundledManifest): ContainerLauncher =
                if (containerPayloads.all { payloadOwned(installation, manifest, it) })
                    Admitted(installation.resolve(containerPayloads.first()))
                else Unavailable
        }
    }

    data object Unavailable : ContainerLauncher
}

private val containerPayloads =
    listOf(
        "share/kast/container/bin/kast-container",
        "share/kast/container/bin/kast-container.lock",
        "share/kast/container/packaging/dev_container.py",
    )

internal fun runInstalledContainer(
    root: Path,
    arguments: List<String>,
    directory: Path,
    environment: Map<String, String>,
): Int {
    val installation = selectedInstallation(root)
    val launcher =
        when (val manifest = readBundledManifest(installation)) {
            is BundledManifestRead.Read -> ContainerLauncher.Admitted.fromInstallation(installation, manifest.manifest)
            BundledManifestRead.Invalid,
            BundledManifestRead.Unavailable -> ContainerLauncher.Unavailable
        }
    return when (launcher) {
        is ContainerLauncher.Admitted -> {
            val help =
                arguments in listOf(listOf("--help"), listOf("-h")) ||
                    (arguments.size == 2 &&
                        arguments.first() in setOf("build", "shell", "run", "export", "status") &&
                        arguments.last() in setOf("--help", "-h"))
            val command =
                if (help) listOf("python3", "-I", launcher.path.toString())
                else
                    listOf("uv", "run", "--locked", "--no-project", "--no-config", "--script", launcher.path.toString())
            try {
                runOwnedPublicQueryChild(command + arguments, directory, environment)
            } catch (_: IOException) {
                rejectContainerStartup(ContainerStartupFailure.LAUNCH_FAILED)
            }
        }
        ContainerLauncher.Unavailable -> rejectContainerStartup(ContainerStartupFailure.PAYLOAD_REJECTED)
    }
}

private enum class ContainerStartupFailure(val guidance: String) {
    LAUNCH_FAILED("Cannot start the container launcher. Examine Python and uv on PATH."),
    PAYLOAD_REJECTED("Container tools are unavailable or changed. Reinstall Kast."),
}

private fun rejectContainerStartup(failure: ContainerStartupFailure): Int {
    System.err.println("kast container: outcome=${failure.name}; ${failure.guidance}")
    return 1
}
