package io.github.amichne.kast.distribution.managed.network

import io.github.amichne.kast.distribution.contract.network.TrustProvenance
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Properties

sealed interface NetworkPolicyObservation {
    data object Absent : NetworkPolicyObservation
    data class Recorded(val authority: RecordedNetworkAuthority) : NetworkPolicyObservation
    data class Rejected(val failure: NetworkPolicyObservationFailure) : NetworkPolicyObservation
}

sealed interface RecordedNetworkAuthority {
    data object Gradle : RecordedNetworkAuthority
    data object TargetJvm : RecordedNetworkAuthority
    class Derived internal constructor(val digest: String, val sources: Set<TrustProvenance>, val path: Path) : RecordedNetworkAuthority
}

enum class NetworkPolicyObservationFailure { UNREADABLE, MALFORMED, ARTIFACT_UNAVAILABLE }

/** Reads only a bounded policy receipt and artifact presence; never initializes JSSE or writes. */
object NetworkPolicyReceipt {
    fun observe(directory: Path): NetworkPolicyObservation = try {
        val path = directory.resolve("policy.properties")
        when {
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> NetworkPolicyObservation.Absent
            Files.isSymbolicLink(directory) || Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > 4096 ->
                NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.UNREADABLE)
            else -> {
                val properties = Properties()
                Files.newInputStream(path).use(properties::load)
                if (properties.stringPropertyNames() != setOf("digest", "sources")) {
                    return NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.MALFORMED)
                }
                val digest = properties.getProperty("digest")
                val names = properties.getProperty("sources").split(',').filter(String::isNotEmpty)
                val sources = names.map { name -> TrustProvenance.entries.singleOrNull { it.name == name }
                    ?: return NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.MALFORMED) }.toSet()
                val authority = when (digest) {
                    "explicit-gradle" -> if (sources == setOf(TrustProvenance.EXPLICIT_GRADLE)) RecordedNetworkAuthority.Gradle
                        else return NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.MALFORMED)
                    "target-jvm" -> if (sources.isEmpty()) RecordedNetworkAuthority.TargetJvm
                        else return NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.MALFORMED)
                    else -> {
                        if (!digest.matches(Regex("[0-9a-f]{64}")) || sources.isEmpty()) {
                            return NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.MALFORMED)
                        }
                        val artifact = directory.resolve("sha256-$digest.jks")
                        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
                            return NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.ARTIFACT_UNAVAILABLE)
                        }
                        RecordedNetworkAuthority.Derived(digest, sources, artifact)
                    }
                }
                NetworkPolicyObservation.Recorded(authority)
            }
        }
    } catch (_: IOException) {
        NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.UNREADABLE)
    } catch (_: IllegalArgumentException) {
        NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.MALFORMED)
    } catch (_: SecurityException) {
        NetworkPolicyObservation.Rejected(NetworkPolicyObservationFailure.UNREADABLE)
    }
}
