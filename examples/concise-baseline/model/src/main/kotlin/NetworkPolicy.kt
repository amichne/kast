package kast.baseline.model

/** Raw maps exist only at the configuration boundary. Ordering is defined here, not by callers. */
enum class NetworkSource { KAST_OVERRIDE, JVM_PROPERTIES, GRADLE_USER, GRADLE_PROJECT }
enum class NetworkConsumer { SIDECAR_CLIENT, GRADLE_DAEMON }
data class RawNetworkLayer(val source: NetworkSource, val properties: Map<String, String>)
enum class NetworkFailure { DUPLICATE_SOURCE, MALFORMED_PROPERTY, INCOMPLETE_TRUSTSTORE,
    UNSUPPORTED_PROVIDER, UNSUPPORTED_PROXY_AUTHENTICATION, INCOMPLETE_PROXY, INVALID_PROXY, INVALID_PATH }

class Secret private constructor(private val characters: CharArray) {
    /** A temporary copy is available only at the credential-consuming adapter boundary. */
    fun <T> useAtBoundary(block: (CharArray) -> T): T {
        val copy = characters.copyOf()
        return try { block(copy) } finally { copy.fill('\u0000') }
    }
    override fun toString(): String = "<redacted>"
    companion object { fun capture(raw: String): Secret = Secret(raw.toCharArray()) }
}

@JvmInline
value class AbsolutePath private constructor(val value: String) {
    companion object {
        /** Lexical proof only. Physical/readability/content proof belongs to the file adapter. */
        fun parse(raw: String): Refinement<AbsolutePath, NetworkFailure> =
            if (raw.startsWith('/') && raw.length in 2..4096 &&
                raw.none { it == '\u0000' || it == '\n' || it == '\r' } &&
                raw.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." })
                Refinement.Refined(AbsolutePath(raw))
            else Refinement.Rejected(NetworkFailure.INVALID_PATH)
    }
}

enum class StoreFormat { PLATFORM_DEFAULT, JKS, PKCS12 }
sealed interface StorePassword {
    data object Unspecified : StorePassword
    class Supplied(val secret: Secret) : StorePassword {
        override fun toString(): String = "Supplied(<redacted>)"
    }
}
sealed interface TrustPolicy {
    data object PlatformDefault : TrustPolicy
    /** Explicitly selected donor home, not a search through arbitrary installed JVMs. */
    class JvmDefault(val home: AbsolutePath) : TrustPolicy {
        override fun toString(): String = "JvmDefault(<selected-home>)"
    }
    class Store internal constructor(val file: AbsolutePath, val format: StoreFormat,
        val password: StorePassword, val source: NetworkSource) : TrustPolicy {
        override fun toString(): String = "Store(source=$source, format=$format)"
    }
}
@JvmInline
value class ProxyHost private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ProxyHost, NetworkFailure> =
            if (raw.length in 1..253 && Regex("[A-Za-z0-9_.-]+").matches(raw))
                Refinement.Refined(ProxyHost(raw))
            else Refinement.Rejected(NetworkFailure.INVALID_PROXY)
    }
}
@JvmInline
value class ProxyPort private constructor(val value: Int) {
    companion object {
        fun parse(raw: String): Refinement<ProxyPort, NetworkFailure> =
            raw.toIntOrNull()?.takeIf { it in 1..65535 }?.let { Refinement.Refined(ProxyPort(it)) }
                ?: Refinement.Rejected(NetworkFailure.INVALID_PROXY)
    }
}
@JvmInline
value class NonProxyHosts private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<NonProxyHosts, NetworkFailure> =
            if (raw.length <= 4096 && raw.all { it.isLetterOrDigit() || it in ".*|-:_[]" })
                Refinement.Refined(NonProxyHosts(raw))
            else Refinement.Rejected(NetworkFailure.INVALID_PROXY)
    }
}
sealed interface ProxyRoute {
    data object Unspecified : ProxyRoute
    data class Forward(val host: ProxyHost, val port: ProxyPort) : ProxyRoute
}
sealed interface ProxyBypass {
    data object Unspecified : ProxyBypass
    data class Configured(val hosts: NonProxyHosts) : ProxyBypass
}
sealed interface ProxyPolicy {
    data object PlatformDefault : ProxyPolicy
    class Configured internal constructor(val http: ProxyRoute, val https: ProxyRoute,
        val bypass: ProxyBypass, val source: NetworkSource) : ProxyPolicy {
        override fun toString(): String = "Configured(source=$source)"
    }
}
class NetworkPolicy internal constructor(val consumer: NetworkConsumer, val trust: TrustPolicy,
    val proxy: ProxyPolicy) {
    override fun toString(): String = "NetworkPolicy(consumer=$consumer, trust=$trust, proxy=$proxy)"
}

object NetworkPolicyResolver {
    private const val TRUST = "javax.net.ssl.trustStore"
    private val trustKeys = setOf(TRUST, "${TRUST}Type", "${TRUST}Password", "${TRUST}Provider")

    /** Each consumer supplies its own layers. Client policy is never copied into daemon policy. */
    fun resolve(consumer: NetworkConsumer, layers: List<RawNetworkLayer>,
        fallback: TrustPolicy = TrustPolicy.PlatformDefault): Refinement<NetworkPolicy, NetworkFailure> {
        if (layers.map { it.source }.distinct().size != layers.size)
            return Refinement.Rejected(NetworkFailure.DUPLICATE_SOURCE)
        val ordered = layers.sortedBy { it.source.ordinal }
        val selected = ordered.firstOrNull { layer -> layer.properties.keys.any { it in trustKeys } }
        val trust = if (selected == null) fallback else {
            val values = selected.properties
            if (values.keys.any { it in trustKeys && (values.getValue(it).length > 65536 ||
                    '\u0000' in values.getValue(it)) })
                return Refinement.Rejected(NetworkFailure.MALFORMED_PROPERTY)
            if (values.containsKey("${TRUST}Provider"))
                return Refinement.Rejected(NetworkFailure.UNSUPPORTED_PROVIDER)
            val raw = values[TRUST] ?: return Refinement.Rejected(NetworkFailure.INCOMPLETE_TRUSTSTORE)
            val file = when (val parsed = AbsolutePath.parse(raw)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            val format = when (values["${TRUST}Type"]) {
                null -> StoreFormat.PLATFORM_DEFAULT
                "JKS" -> StoreFormat.JKS
                "PKCS12" -> StoreFormat.PKCS12
                else -> return Refinement.Rejected(NetworkFailure.UNSUPPORTED_PROVIDER)
            }
            val password = values["${TRUST}Password"]?.let { StorePassword.Supplied(Secret.capture(it)) }
                ?: StorePassword.Unspecified
            TrustPolicy.Store(file, format, password, selected.source)
        }
        val proxyKeys = setOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort",
            "http.nonProxyHosts", "http.proxyUser", "http.proxyPassword", "https.proxyUser", "https.proxyPassword")
        val proxyLayer = ordered.firstOrNull { layer -> layer.properties.keys.any { it in proxyKeys } }
        val proxy = if (proxyLayer == null) ProxyPolicy.PlatformDefault else {
            val values = proxyLayer.properties
            if (values.keys.any { it in proxyKeys && (it.endsWith("User") || it.endsWith("Password")) })
                return Refinement.Rejected(NetworkFailure.UNSUPPORTED_PROXY_AUTHENTICATION)
            val http = when (val parsed = proxyRoute("http", "80", values)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            val https = when (val parsed = proxyRoute("https", "443", values)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            val bypass = when (val raw = values["http.nonProxyHosts"]) {
                null -> ProxyBypass.Unspecified
                else -> when (val parsed = NonProxyHosts.parse(raw)) {
                    is Refinement.Refined -> ProxyBypass.Configured(parsed.value)
                    is Refinement.Rejected -> return parsed
                }
            }
            ProxyPolicy.Configured(http, https, bypass, proxyLayer.source)
        }
        return Refinement.Refined(NetworkPolicy(consumer, trust, proxy))
    }
    private fun proxyRoute(scheme: String, defaultPort: String, values: Map<String, String>)
        : Refinement<ProxyRoute, NetworkFailure> {
        val host = values["$scheme.proxyHost"]
        if (host == null) return if (values.containsKey("$scheme.proxyPort"))
            Refinement.Rejected(NetworkFailure.INCOMPLETE_PROXY)
        else Refinement.Refined(ProxyRoute.Unspecified)
        val admittedHost = when (val parsed = ProxyHost.parse(host)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
        val port = when (val parsed = ProxyPort.parse(values["$scheme.proxyPort"] ?: defaultPort)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
        return Refinement.Refined(ProxyRoute.Forward(admittedHost, port))
    }

}
