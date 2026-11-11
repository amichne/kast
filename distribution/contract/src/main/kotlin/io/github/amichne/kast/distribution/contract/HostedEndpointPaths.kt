package io.github.amichne.kast.distribution.contract

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** One short namespace for both desktop and private Hosts; HOME and target remain distinct identities. */
object HostedEndpointPaths {
    private const val DIGEST_BYTES = 16

    enum class Family(val directory: String) {
        SEMANTIC("h"),
        LIFECYCLE("l"),
    }

    fun root(home: Path): Path = Path.of("/tmp").toRealPath().resolve("kast-${digest(home.toString())}")

    fun family(home: Path, family: Family): Path = root(home).resolve(family.directory)

    fun directory(home: Path, family: Family, target: String): Path = family(home, family).resolve(digest(target))

    fun digest(identity: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()), 0, DIGEST_BYTES)
}
