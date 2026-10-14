package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.configuration.BrokerPublicEndpointMode
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOwner
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.contract.configuration.SavedConfigurationDocument
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Passive management uses saved installation policy, never the caller's ambient endpoint override. */
internal fun runtimeSocket(
    installation: Path,
    codexHome: String,
): Refinement<Path, RuntimeObservationFailure> {
    if (!validRuntimeHome(codexHome)) return Refinement.Rejected(RuntimeObservationFailure.MANIFEST_REJECTED)
    val configuration = installation.resolve("config/environment")
    val bytes =
        if (Files.notExists(configuration, NOFOLLOW_LINKS)) byteArrayOf()
        else
            readBoundedFile(configuration, SavedConfigurationDocument.MAXIMUM_BYTES.toLong())?.toByteArray()
                ?: return Refinement.Rejected(RuntimeObservationFailure.CONFIGURATION_UNAVAILABLE)
    val saved =
        when (val admitted = SavedConfigurationDocument.parse(bytes)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(RuntimeObservationFailure.CONFIGURATION_REJECTED)
        }
    val resolved =
        when (val admitted = ResolvedKastConfiguration.resolve(saved.configurationSources(emptyMap()))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(RuntimeObservationFailure.CONFIGURATION_REJECTED)
        }
    val mode =
        when (
            val admitted =
                BrokerPublicEndpointMode.admit(
                    resolved
                        .ownerInputs(ConfigurationOwner.APP_SERVER)[
                            ConfigurationParameter.APP_SERVER_PUBLIC_ENDPOINT.key]
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(RuntimeObservationFailure.CONFIGURATION_REJECTED)
        }
    return when (mode) {
        BrokerPublicEndpointMode.CODEX_CONTROL ->
            Refinement.Refined(Path.of(codexHome).resolve("app-server-control/app-server-control.sock"))
        BrokerPublicEndpointMode.PRIVATE -> privateRuntimeSocket(installation)
    }
}

private fun validRuntimeHome(raw: String): Boolean =
    try {
        val home = Path.of(raw)
        home.isAbsolute && home.normalize() == home
    } catch (_: IllegalArgumentException) {
        false
    }

private fun privateRuntimeSocket(installation: Path): Refinement<Path, RuntimeObservationFailure> {
    val physical = installation.resolve("state/run/c.sock")
    if (physical.toString().toByteArray().size < SOCKET_PATH_LIMIT_BYTES) return Refinement.Refined(physical)
    val directory = physical.parent
    val hash =
        MessageDigest.getInstance("SHA-256")
            .digest(directory.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    val alias = Path.of("/tmp/kast-uds-$hash")
    return try {
        if (Files.isSymbolicLink(alias) && Files.readSymbolicLink(alias) == directory)
            Refinement.Refined(alias.resolve("c.sock"))
        else Refinement.Rejected(RuntimeObservationFailure.SOCKET_UNAVAILABLE)
    } catch (_: Exception) {
        Refinement.Rejected(RuntimeObservationFailure.SOCKET_UNAVAILABLE)
    }
}

private const val SOCKET_PATH_LIMIT_BYTES = 104
