package io.github.amichne.kast.appserver

import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** Physical executable selection is retained; neither child launch nor routing follows `current`. */
internal class BrokerInstallationLayout private constructor(
    val root: Path,
    val hostProfile: String,
) {
    val state: Path = root.resolve("state")
    val broker: Path = state.resolve("broker").resolve(hostProfile)
    val run: Path = state.resolve("run")
    val publicSocket: Path = BrokerEndpointAliases.transportPath(run.resolve("c.sock"))
    val upstreamSocket: Path = BrokerEndpointAliases.transportPath(run.resolve("u.sock"))

    companion object {
        /** The caller has established the physical installed executable and selected host home. */
        fun from(kast: Path, codexHome: Path): BrokerInstallationLayout = BrokerInstallationLayout(
            kast.parent.parent,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                codexHome.toString().toByteArray(StandardCharsets.UTF_8),
            )).take(16),
        )
    }
}
