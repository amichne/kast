package io.github.amichne.kast.distribution.contract.network

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

object KastNetworkPropertyNamespace {
    const val DAEMON_PREFIX: String = "kast.network.daemon."
}

/** The only JVM networking properties admitted across the runtime isolation boundary. */
enum class NetworkProperty(val key: String) {
    TRUST_STORE("javax.net.ssl.trustStore"),
    TRUST_STORE_TYPE("javax.net.ssl.trustStoreType"),
    TRUST_STORE_PROVIDER("javax.net.ssl.trustStoreProvider"),
    TRUST_STORE_PASSWORD("javax.net.ssl.trustStorePassword"),
    HTTP_HOST("http.proxyHost"),
    HTTP_PORT("http.proxyPort"),
    BYPASS("http.nonProxyHosts"),
    HTTPS_HOST("https.proxyHost"),
    HTTPS_PORT("https.proxyPort");

    val group: NetworkPropertyGroup
        get() =
            when (this) {
                TRUST_STORE,
                TRUST_STORE_TYPE,
                TRUST_STORE_PROVIDER,
                TRUST_STORE_PASSWORD -> NetworkPropertyGroup.TRUST
                HTTP_HOST,
                HTTP_PORT -> NetworkPropertyGroup.HTTP_PROXY
                HTTPS_HOST,
                HTTPS_PORT -> NetworkPropertyGroup.HTTPS_PROXY
                BYPASS -> NetworkPropertyGroup.BYPASS
            }

    val isTrust: Boolean
        get() = group == NetworkPropertyGroup.TRUST
}

enum class NetworkPropertyGroup {
    TRUST,
    HTTP_PROXY,
    HTTPS_PROXY,
    BYPASS,
}

enum class NetworkConfigurationFailure {
    UNKNOWN_PROPERTY,
    INVALID_VALUE,
    INVALID_PATH,
    INVALID_PORT,
    INCOMPLETE_TRUST,
    INCOMPLETE_PROXY,
    CONFIGURATION_UNAVAILABLE,
    MALFORMED_JVM_ARGUMENTS,
}

/** Secrets use reference identity and never disclose their contents through equality or rendering. */
class TrustStorePassword private constructor(private val characters: CharArray?) {
    fun <T> useAtJsseBoundary(operation: (CharArray?) -> T): T {
        val copy = characters?.copyOf()
        return try {
            operation(copy)
        } finally {
            copy?.fill('\u0000')
        }
    }

    override fun toString(): String = "TrustStorePassword(redacted)"

    companion object {
        val Unspecified = TrustStorePassword(null)

        fun fromBoundary(raw: String): TrustStorePassword = TrustStorePassword(raw.toCharArray())
    }
}

/** Validated property values stay owned by one closed configuration until a JVM/property-file boundary. */
class NetworkConfiguration private constructor(private val values: Map<NetworkProperty, String>) {
    val trust: TrustSelection =
        values[NetworkProperty.TRUST_STORE]?.let { raw ->
            TrustSelection.Explicit(
                Path.of(raw),
                values[NetworkProperty.TRUST_STORE_TYPE] ?: "JKS",
                values[NetworkProperty.TRUST_STORE_PROVIDER],
                values[NetworkProperty.TRUST_STORE_PASSWORD]?.let(TrustStorePassword::fromBoundary)
                    ?: TrustStorePassword.Unspecified,
            )
        } ?: TrustSelection.RuntimeDefault

    /** Raw extraction is permitted only when installing JSSE or Gradle's JVM properties. */
    fun atJvmBoundary(): Map<String, String> = values.mapKeys { it.key.key }

    /** A consumer-owned trust bundle suppresses the entire fallback trust bundle. */
    fun fallbackBehind(owner: NetworkConfiguration): NetworkConfiguration {
        val fallback = values.filterKeys { key ->
            owner.values.keys.none { it.group == key.group }
        }
        return NetworkConfiguration(fallback + owner.values)
    }

    /** Gradle owns these explicit values already; return only missing Kast fallback properties. */
    fun missingFrom(owner: NetworkConfiguration): NetworkConfiguration =
        NetworkConfiguration(values.filterKeys { key -> owner.values.keys.none { it.group == key.group } })

    fun withDerivedTrust(path: Path): Refinement<NetworkConfiguration, NetworkConfigurationFailure> =
        parse(
            values.filterKeys { !it.isTrust }.mapKeys { it.key.key } +
                mapOf(
                    NetworkProperty.TRUST_STORE.key to path.toString(),
                    NetworkProperty.TRUST_STORE_TYPE.key to "JKS",
                    NetworkProperty.TRUST_STORE_PASSWORD.key to "",
                )
        )

    override fun toString(): String = "NetworkConfiguration(properties=${values.keys})"

    companion object {
        val Empty = NetworkConfiguration(emptyMap())

        /** Raw properties become a closed, bounded configuration; malformed/unknown inputs reject. */
        fun parse(raw: Map<String, String>): Refinement<NetworkConfiguration, NetworkConfigurationFailure> {
            val values = linkedMapOf<NetworkProperty, String>()
            for ((key, value) in raw) {
                val property =
                    NetworkProperty.entries.singleOrNull { it.key == key }
                        ?: return Refinement.Rejected(NetworkConfigurationFailure.UNKNOWN_PROPERTY)
                if (value.length > 8192 || value.any { it == '\u0000' || it == '\n' || it == '\r' }) {
                    return Refinement.Rejected(NetworkConfigurationFailure.INVALID_VALUE)
                }
                if (property != NetworkProperty.TRUST_STORE_PASSWORD && value.isBlank()) {
                    return Refinement.Rejected(NetworkConfigurationFailure.INVALID_VALUE)
                }
                when (property) {
                    NetworkProperty.TRUST_STORE -> {
                        val path =
                            try {
                                Path.of(value)
                            } catch (_: java.nio.file.InvalidPathException) {
                                return Refinement.Rejected(NetworkConfigurationFailure.INVALID_PATH)
                            }
                        if (!path.isAbsolute || path.normalize() != path)
                            return Refinement.Rejected(NetworkConfigurationFailure.INVALID_PATH)
                    }
                    NetworkProperty.TRUST_STORE_TYPE ->
                        if (value !in setOf("JKS", "PKCS12")) {
                            return Refinement.Rejected(NetworkConfigurationFailure.INVALID_VALUE)
                        }
                    NetworkProperty.HTTP_PORT,
                    NetworkProperty.HTTPS_PORT ->
                        if (value.toIntOrNull() !in 1..65535) {
                            return Refinement.Rejected(NetworkConfigurationFailure.INVALID_PORT)
                        }
                    NetworkProperty.HTTP_HOST,
                    NetworkProperty.HTTPS_HOST ->
                        if (!value.matches(Regex("[a-zA-Z0-9._:\\[\\]-]+"))) {
                            return Refinement.Rejected(NetworkConfigurationFailure.INVALID_VALUE)
                        }
                    NetworkProperty.BYPASS ->
                        if (!value.matches(Regex("[a-zA-Z0-9.*_|:\\[\\]-]+"))) {
                            return Refinement.Rejected(NetworkConfigurationFailure.INVALID_VALUE)
                        }
                    NetworkProperty.TRUST_STORE_PROVIDER ->
                        if (!value.matches(Regex("[a-zA-Z0-9._-]+"))) {
                            return Refinement.Rejected(NetworkConfigurationFailure.INVALID_VALUE)
                        }
                    NetworkProperty.TRUST_STORE_PASSWORD -> Unit
                }
                values[property] = value
            }
            if (values.keys.any { it.isTrust } && NetworkProperty.TRUST_STORE !in values) {
                return Refinement.Rejected(NetworkConfigurationFailure.INCOMPLETE_TRUST)
            }
            if (
                (NetworkProperty.HTTP_PORT in values && NetworkProperty.HTTP_HOST !in values) ||
                    (NetworkProperty.HTTPS_PORT in values && NetworkProperty.HTTPS_HOST !in values)
            ) {
                return Refinement.Rejected(NetworkConfigurationFailure.INCOMPLETE_PROXY)
            }
            return Refinement.Refined(NetworkConfiguration(values.toMap()))
        }
    }
}

sealed interface TrustSelection {
    data object RuntimeDefault : TrustSelection

    class Explicit
    internal constructor(
        val path: Path,
        val type: String,
        val provider: String?,
        val password: TrustStorePassword,
    ) : TrustSelection {
        override fun toString(): String = "ExplicitTrust(path=$path,type=$type,provider=$provider)"
    }
}

enum class NetworkConsumer {
    SIDECAR_TOOLING_CLIENT,
    GRADLE_DAEMON,
}

enum class TrustProvenance {
    EXPLICIT_KAST,
    EXPLICIT_GRADLE,
    DONOR_JVM,
    INTELLIJ_ACCEPTED,
    TARGET_JVM,
}
