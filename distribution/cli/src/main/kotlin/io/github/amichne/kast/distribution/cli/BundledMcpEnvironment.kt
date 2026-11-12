package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Only explicit MCP provisioning admits the private interpreter and its complete import inventory. */
internal sealed interface BundledMcpEnvironment {
    data object NotSelected : BundledMcpEnvironment

    class Selected private constructor(private val installation: Path) : BundledMcpEnvironment {
        fun observePreparation(observation: ProcessObservation): PluginObservation<Unit> =
            observeMcpPreparation(installation, observation)

        fun prepareArguments(): List<String> =
            listOf(
                installation.resolve(MCP_PYTHON).toString(),
                "-I",
                "-S",
                "-B",
                installation.resolve(MCP_HELPER).toString(),
                "prepare",
                "--install-root",
                installation.toString(),
            )

        companion object {
            fun admit(installation: Path, manifest: BundledManifest): PluginObservation<Selected> =
                try {
                    if (admitMcpInventory(installation, manifest)) PluginObservation.Observed(Selected(installation))
                    else PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                } catch (_: IOException) {
                    PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                } catch (_: SecurityException) {
                    PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                }
        }
    }
}

private const val MCP_PYTHON_ROOT = "share/kast/private-python"
private const val MCP_PYTHON = "$MCP_PYTHON_ROOT/bin/python3.12"
private const val MCP_HELPER = "share/kast/mcp_environment.py"
private const val MCP_SCRIPTS = "share/kast/agent-tools/compositions/mcp/plugins/kast/skills/kast/scripts"
private const val FILE_PERMISSIONS = 511
private const val GROUP_WORLD_WRITE = 18
private val mcpFiles = setOf(MCP_HELPER, "share/kast/mcp-requirements.txt", "share/kast/mcp-requirements.txt.lock")

private fun admitMcpInventory(installation: Path, manifest: BundledManifest): Boolean {
    if (!ownedMcpPath(installation) || !ownedMcpPath(installation.resolve("installation.json"))) return false
    val files = mutableListOf<Path>()
    for (relative in listOf(MCP_PYTHON_ROOT, MCP_SCRIPTS)) {
        val tree = installation.resolve(relative)
        if (!Files.isDirectory(tree, NOFOLLOW_LINKS)) return false
        val paths = Files.walk(tree).use { it.limit(ControlDistributionLimits.maximumEntryCount.toLong() + 1).toList() }
        if (paths.size > ControlDistributionLimits.maximumEntryCount || paths.any { !ownedMcpPath(it) }) return false
        files += paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
    }
    files += mcpFiles.map(installation::resolve)
    val expected =
        manifest.payloadFiles.filter {
            it.path in mcpFiles || it.path.startsWith("$MCP_PYTHON_ROOT/") || it.path.startsWith("$MCP_SCRIPTS/")
        }
    return mcpInventoryMatches(installation, expected, files)
}

private fun mcpInventoryMatches(installation: Path, expected: List<BundledPayload>, files: List<Path>): Boolean {
    val names = files.map { installation.relativize(it).toString() }.toSet()
    if (
        files.size > ControlDistributionLimits.maximumEntryCount ||
            expected.size != names.size ||
            expected.map { it.path }.toSet() != names
    )
        return false
    if (!names.containsAll(setOf(MCP_PYTHON, "$MCP_SCRIPTS/server.py", "$MCP_SCRIPTS/server.py.lock"))) return false
    if (files.any { !Files.isRegularFile(it, NOFOLLOW_LINKS) || !ownedMcpPath(it) }) return false
    if (files.sumOf(Files::size) > ControlDistributionLimits.maximumPayloadBytes) return false
    return Files.isExecutable(installation.resolve(MCP_PYTHON)) &&
        expected.all {
            val path = installation.resolve(it.path)
            it.sha256 == "sha256:${sha256(path)}" &&
                it.mode ==
                    (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Number).toInt().and(FILE_PERMISSIONS)
        }
}

private fun ownedMcpPath(path: Path): Boolean {
    if (path.toRealPath() != path) return false
    val attributes = Files.readAttributes(path, "unix:uid,nlink,mode", NOFOLLOW_LINKS)
    if (
        (attributes.getValue("uid") as Number).toLong() != com.sun.security.auth.module.UnixSystem().uid ||
            (attributes.getValue("mode") as Number).toInt().and(GROUP_WORLD_WRITE) != 0
    )
        return false
    return Files.isDirectory(path, NOFOLLOW_LINKS) ||
        (Files.isRegularFile(path, NOFOLLOW_LINKS) && (attributes.getValue("nlink") as Number).toInt() == 1)
}
