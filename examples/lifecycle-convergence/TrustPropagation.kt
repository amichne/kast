package lifecycle.convergence

import java.nio.file.Path

/** A value whose only meaning is one admitted readable truststore. */
@JvmInline
value class TrustStorePath private constructor(val value: Path) {
    companion object {
        fun admit(path: Path): TrustStoreAdmission =
            if (path.isAbsolute && path.normalize() == path) {
                TrustStoreAdmission.Admitted(TrustStorePath(path))
            } else {
                TrustStoreAdmission.Rejected(TrustStoreFailure.InvalidPath)
            }
    }
}

sealed interface TrustStoreAdmission {
    data class Admitted(val path: TrustStorePath) : TrustStoreAdmission
    data class Rejected(val failure: TrustStoreFailure) : TrustStoreAdmission
}

enum class TrustStoreFailure {
    InvalidPath,
    MissingExplicitStore,
    UnsupportedStoreType,
    MaterializationFailed,
}

/** Authority from which Kast is allowed to derive certificate trust. */
sealed interface TrustMaterialAuthority {
    data class ExplicitJsse(
        val store: TrustStorePath,
        val type: String?,
    ) : TrustMaterialAuthority

    data class DonorJvm(
        val javaHome: Path,
    ) : TrustMaterialAuthority

    data class IntelliJAcceptedCertificates(
        val configHome: Path,
    ) : TrustMaterialAuthority

    data object TargetRuntimeDefault : TrustMaterialAuthority
}

/** Consumer identity matters because the Tooling API client and daemon are different JVMs. */
enum class NetworkConsumer {
    SidecarToolingClient,
    GradleDaemon,
}

/**
 * One resolved policy; no Boolean says whether it is trusted. Construction establishes which
 * authority owns the consumer's trust decision.
 */
sealed interface ResolvedTrustPolicy {
    data class DerivedStore(
        val consumer: NetworkConsumer,
        val store: TrustStorePath,
        val sources: List<TrustMaterialAuthority>,
    ) : ResolvedTrustPolicy

    data class ExistingExplicitStore(
        val consumer: NetworkConsumer,
        val authority: TrustMaterialAuthority.ExplicitJsse,
    ) : ResolvedTrustPolicy

    data class RuntimeDefault(
        val consumer: NetworkConsumer,
    ) : ResolvedTrustPolicy
}

/** Closed system properties Kast is permitted to carry across the isolation boundary. */
enum class AdmittedNetworkProperty(val key: String) {
    TrustStore("javax.net.ssl.trustStore"),
    TrustStoreType("javax.net.ssl.trustStoreType"),
    TrustStoreProvider("javax.net.ssl.trustStoreProvider"),
    HttpProxyHost("http.proxyHost"),
    HttpProxyPort("http.proxyPort"),
    HttpNonProxyHosts("http.nonProxyHosts"),
    HttpsProxyHost("https.proxyHost"),
    HttpsProxyPort("https.proxyPort"),
}

class NetworkPropertySet private constructor(
    val values: Map<AdmittedNetworkProperty, String>,
) {
    companion object {
        fun parse(raw: Map<String, String>): NetworkPropertySet = NetworkPropertySet(
            AdmittedNetworkProperty.entries.mapNotNull { property ->
                raw[property.key]?.takeIf(String::isNotBlank)?.let { property to it }
            }.toMap(),
        )
    }
}

/**
 * Explicit Gradle configuration wins for the Gradle daemon. The sidecar client cannot inherit it
 * accidentally through ambient JVM options; it receives one resolved policy deliberately.
 */
object TrustPolicyResolver {
    fun resolve(
        consumer: NetworkConsumer,
        explicitGradle: TrustMaterialAuthority.ExplicitJsse?,
        derived: TrustStorePath?,
        sources: List<TrustMaterialAuthority>,
    ): ResolvedTrustPolicy = when {
        consumer == NetworkConsumer.GradleDaemon && explicitGradle != null ->
            ResolvedTrustPolicy.ExistingExplicitStore(consumer, explicitGradle)

        derived != null -> ResolvedTrustPolicy.DerivedStore(consumer, derived, sources)

        else -> ResolvedTrustPolicy.RuntimeDefault(consumer)
    }
}

/**
 * Production adapter sketch:
 *
 * 1. discover explicit JSSE configuration without forwarding arbitrary JVM options;
 * 2. derive a private certificate-only store under the exact sidecar cache;
 * 3. set SidecarToolingClient JSSE properties before Gradle integration initializes networking;
 * 4. apply GradleDaemon policy at the Gradle launch/settings boundary only when repository/user
 *    Gradle configuration has not already established a stronger explicit policy.
 */
fun resolvedJvmProperties(policy: ResolvedTrustPolicy): Map<String, String> = when (policy) {
    is ResolvedTrustPolicy.DerivedStore -> mapOf(
        "javax.net.ssl.trustStore" to policy.store.value.toString(),
    )
    is ResolvedTrustPolicy.ExistingExplicitStore -> mapOf(
        "javax.net.ssl.trustStore" to policy.authority.store.value.toString(),
    ) + listOfNotNull(
        policy.authority.type?.let { "javax.net.ssl.trustStoreType" to it },
    )
    is ResolvedTrustPolicy.RuntimeDefault -> emptyMap()
}
