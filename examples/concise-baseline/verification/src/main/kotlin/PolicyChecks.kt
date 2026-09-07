package kast.baseline.verification

import kast.baseline.model.*

fun <T, F> Refinement<T, F>.valueOrFail(): T = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected rejection: $failure")
}

fun policyChecks(): List<String> {
    val checks = mutableListOf<String>()
    fun prove(name: String, predicate: Boolean) { check(predicate) { name }; checks += name }
    val explicit = RawNetworkLayer(NetworkSource.JVM_PROPERTIES, mapOf(
        "javax.net.ssl.trustStore" to "/enterprise/ca.p12",
        "javax.net.ssl.trustStoreType" to "PKCS12",
        "javax.net.ssl.trustStorePassword" to "fixture-secret",
        "java.security.properties" to "/untrusted/security",
        "idea.plugins.path" to "/untrusted/plugins"))
    val policy = NetworkPolicyResolver.resolve(NetworkConsumer.SIDECAR_CLIENT, listOf(explicit)).valueOrFail()
    prove("explicit-truststore-is-retained", policy.trust is TrustPolicy.Store)
    prove("policy-rendering-excludes-secrets-and-paths", !policy.toString().contains("fixture-secret") &&
        !policy.toString().contains("/enterprise") && !policy.toString().contains("/untrusted"))
    val user = RawNetworkLayer(NetworkSource.GRADLE_USER, mapOf("javax.net.ssl.trustStore" to "/user/ca.p12"))
    val project = RawNetworkLayer(NetworkSource.GRADLE_PROJECT, mapOf("javax.net.ssl.trustStore" to "/project/ca.p12"))
    val daemon = NetworkPolicyResolver.resolve(NetworkConsumer.GRADLE_DAEMON, listOf(project, user)).valueOrFail()
    prove("gradle-user-precedes-project", (daemon.trust as TrustPolicy.Store).source == NetworkSource.GRADLE_USER)
    prove("consumer-policies-remain-separate", (policy.trust as TrustPolicy.Store).source == NetworkSource.JVM_PROPERTIES)
    prove("duplicate-sources-reject", NetworkPolicyResolver.resolve(NetworkConsumer.GRADLE_DAEMON,
        listOf(user, user)) == Refinement.Rejected(NetworkFailure.DUPLICATE_SOURCE))
    val invalid = RawNetworkLayer(NetworkSource.KAST_OVERRIDE, mapOf("javax.net.ssl.trustStore" to "relative"))
    prove("invalid-explicit-store-never-falls-back", NetworkPolicyResolver.resolve(NetworkConsumer.SIDECAR_CLIENT,
        listOf(invalid, user)) == Refinement.Rejected(NetworkFailure.INVALID_PATH))
    val orphan = RawNetworkLayer(NetworkSource.KAST_OVERRIDE, mapOf("javax.net.ssl.trustStorePassword" to "secret"))
    prove("orphan-store-password-rejects", NetworkPolicyResolver.resolve(NetworkConsumer.SIDECAR_CLIENT,
        listOf(orphan, user)) == Refinement.Rejected(NetworkFailure.INCOMPLETE_TRUSTSTORE))
    val native = RawNetworkLayer(NetworkSource.KAST_OVERRIDE, mapOf("javax.net.ssl.trustStore" to "NONE",
        "javax.net.ssl.trustStoreProvider" to "SunPKCS11"))
    prove("unsupported-native-provider-rejects", NetworkPolicyResolver.resolve(NetworkConsumer.SIDECAR_CLIENT,
        listOf(native)) == Refinement.Rejected(NetworkFailure.UNSUPPORTED_PROVIDER))
    val proxy = RawNetworkLayer(NetworkSource.GRADLE_USER, mapOf("https.proxyHost" to "proxy.example",
        "https.proxyPort" to "8443", "http.nonProxyHosts" to "localhost|*.example", "unrelated" to "secret"))
    val routed = NetworkPolicyResolver.resolve(NetworkConsumer.SIDECAR_CLIENT, listOf(proxy)).valueOrFail()
    prove("proxy-is-typed", routed.proxy is ProxyPolicy.Configured)
    val incomplete = RawNetworkLayer(NetworkSource.GRADLE_USER, mapOf("https.proxyPort" to "8443"))
    prove("orphan-proxy-port-rejects", NetworkPolicyResolver.resolve(NetworkConsumer.SIDECAR_CLIENT,
        listOf(incomplete)) == Refinement.Rejected(NetworkFailure.INCOMPLETE_PROXY))
    val authenticated = RawNetworkLayer(NetworkSource.GRADLE_USER, proxy.properties + ("https.proxyPassword" to "secret"))
    prove("unsupported-proxy-auth-is-not-silently-dropped", NetworkPolicyResolver.resolve(NetworkConsumer.SIDECAR_CLIENT,
        listOf(authenticated)) == Refinement.Rejected(NetworkFailure.UNSUPPORTED_PROXY_AUTHENTICATION))
    prove("no-implicit-topology-command", Operation.TOPOLOGY_BUILD.exposure == Exposure.Internal)
    prove("no-implicit-sync-command", Operation.INDEX_SYNC.exposure == Exposure.Internal)
    prove("exactly-one-baseline", Operation.entries.count { it.exposure == Exposure.Baseline } == 1)
    return checks
}

