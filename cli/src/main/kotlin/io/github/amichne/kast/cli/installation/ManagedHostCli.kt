package io.github.amichne.kast.cli.installation

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val managedHostJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

/** Private installer bridge. No semantic request or project preparation passes through this entry. */
object ManagedHostCli {
    fun run(arguments: List<String>, environment: Map<String, String>): Int {
        val result =
            try {
                if (arguments.size != 1) ManagedHostResponse.Rejected(ManagedHostFailure.INVALID_REQUEST)
                else {
                    val path = Path.of(arguments.single())
                    if (!physicalRequest(path) || Files.size(path) > MAXIMUM_REQUEST_BYTES)
                        ManagedHostResponse.Rejected(ManagedHostFailure.INVALID_REQUEST)
                    else {
                        val request = managedHostJson.decodeFromString<ManagedHostRequest>(Files.readString(path))
                        val home = Path.of(environment["HOME"] ?: System.getProperty("user.home")).toRealPath()
                        executeSelectedManagedHost(request, home, environment)
                    }
                }
            } catch (_: IOException) {
                ManagedHostResponse.Rejected(ManagedHostFailure.INVALID_REQUEST)
            } catch (_: SerializationException) {
                ManagedHostResponse.Rejected(ManagedHostFailure.INVALID_REQUEST)
            } catch (_: IllegalArgumentException) {
                ManagedHostResponse.Rejected(ManagedHostFailure.INVALID_REQUEST)
            }
        println(managedHostJson.encodeToString(result))
        return if (result is ManagedHostResponse.Rejected) 1 else 0
    }
}

private fun executeSelectedManagedHost(
    request: ManagedHostRequest,
    home: Path,
    environment: Map<String, String>,
): ManagedHostResponse {
    val key =
        io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter.MANAGED_HEADLESS_STATE.key
    val sources =
        io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources(
            environment = environment.filterKeys { it == key }
        )
    return when (
        val configuration =
            io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration.resolve(sources)
    ) {
        is io.github.amichne.kast.kernel.Refinement.Rejected ->
            ManagedHostResponse.Rejected(ManagedHostFailure.INVALID_REQUEST)
        is io.github.amichne.kast.kernel.Refinement.Refined ->
            executeManagedHost(request, home, configuration.value.managedHeadlessState)
    }
}

private fun physicalRequest(path: Path): Boolean =
    path.isAbsolute && path.toRealPath() == path && Files.isRegularFile(path, NOFOLLOW_LINKS)

private const val MAXIMUM_REQUEST_BYTES = 65_536L
