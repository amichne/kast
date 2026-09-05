package lifecycle.convergence

import java.util.Properties

/**
 * Narrow effect boundary for JVM properties. Production construction must occur before IntelliJ's
 * Gradle integration initializes Tooling API networking.
 */
fun interface JvmSystemPropertySink {
    fun install(properties: Map<String, String>)
}

object ProcessJvmSystemPropertySink : JvmSystemPropertySink {
    override fun install(properties: Map<String, String>) {
        properties.forEach(System::setProperty)
    }
}

/** Only properties whose semantics are intentionally supported cross the isolation boundary. */
data class EffectiveGradleNetworkConfiguration(
    val properties: NetworkPropertySet,
    val declaresTrustStore: Boolean,
)

/**
 * Example parser for already-loaded effective Gradle properties.
 *
 * Production resolution should honor Gradle's normal user/root precedence, and separately parse
 * `org.gradle.jvmargs` only for the same closed `-D` property set. It must not copy arbitrary JVM
 * arguments or environment variables.
 */
object GradleNetworkConfigurationParser {
    fun parse(properties: Properties): EffectiveGradleNetworkConfiguration {
        val systemProperties = properties.stringPropertyNames()
            .filter { it.startsWith("systemProp.") }
            .associate { name -> name.removePrefix("systemProp.") to properties.getProperty(name) }
        val admitted = NetworkPropertySet.parse(systemProperties)
        return EffectiveGradleNetworkConfiguration(
            admitted,
            AdmittedNetworkProperty.TrustStore in admitted.values,
        )
    }
}

sealed interface SidecarNetworkBootstrap {
    data class Installed(
        val sidecar: ResolvedTrustPolicy,
        val gradle: ResolvedTrustPolicy,
    ) : SidecarNetworkBootstrap

    data class Rejected(val failure: SidecarNetworkBootstrapFailure) : SidecarNetworkBootstrap
}

enum class SidecarNetworkBootstrapFailure {
    ExplicitGradleTrustRejected,
    DerivedTrustUnavailable,
}

/**
 * The key integration idea.
 *
 * Gradle Tooling API uses the sidecar JVM as its client. Gradle documents that client system
 * properties are propagated to the build by default. Installing the resolved network properties
 * before import therefore supplies trust to distribution-download networking and, absent a
 * stronger Gradle-owned policy, to the daemon as well.
 *
 * This is intentionally a one-time bootstrap transition. Later code receives the resolved policy;
 * it does not repeatedly inspect ambient properties.
 */
class SidecarNetworkBootstrapper(
    private val sink: JvmSystemPropertySink,
) {
    fun install(
        explicitGradleTrust: TrustMaterialAuthority.ExplicitJsse?,
        derivedStore: TrustStorePath?,
        sources: List<TrustMaterialAuthority>,
        proxyProperties: NetworkPropertySet,
    ): SidecarNetworkBootstrap {
        val sidecarPolicy = TrustPolicyResolver.resolve(
            NetworkConsumer.SidecarToolingClient,
            explicitGradleTrust,
            derivedStore,
            sources,
        )
        val gradlePolicy = TrustPolicyResolver.resolve(
            NetworkConsumer.GradleDaemon,
            explicitGradleTrust,
            derivedStore,
            sources,
        )

        val sidecarProperties = resolvedJvmProperties(sidecarPolicy) +
            proxyProperties.values.mapKeys { (property, _) -> property.key }
        sink.install(sidecarProperties)

        return SidecarNetworkBootstrap.Installed(sidecarPolicy, gradlePolicy)
    }
}
