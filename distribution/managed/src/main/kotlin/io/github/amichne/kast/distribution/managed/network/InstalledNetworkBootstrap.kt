package io.github.amichne.kast.distribution.managed.network

import io.github.amichne.kast.distribution.contract.network.NetworkConfiguration
import io.github.amichne.kast.distribution.contract.network.NetworkConfigurationFailure
import io.github.amichne.kast.distribution.contract.network.NetworkConsumer
import io.github.amichne.kast.distribution.contract.network.NetworkProperty
import io.github.amichne.kast.distribution.contract.network.TrustProvenance
import io.github.amichne.kast.distribution.contract.network.TrustSelection
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

sealed interface NetworkBootstrapResult {
    class Prepared
    internal constructor(
        val sidecar: NetworkConfiguration,
        val daemonFallback: NetworkConfiguration,
        val source: TrustProvenance,
    ) : NetworkBootstrapResult {
        /** The only JSSE/system-property effect, completed before IntelliJ initializes networking. */
        fun install() {
            sidecar.atJvmBoundary().forEach(System::setProperty)
            installDaemon()
        }

        fun installDaemon() {
            NetworkProperty.entries.forEach { System.clearProperty(DAEMON_PREFIX + it.key) }
            daemonFallback.atJvmBoundary().forEach { (key, value) -> System.setProperty(DAEMON_PREFIX + key, value) }
        }

        override fun toString(): String = "PreparedNetworkPolicy(source=$source)"
    }

    data class Rejected(val failure: NetworkBootstrapFailure) : NetworkBootstrapResult
}

sealed interface NetworkBootstrapFailure {
    data class Configuration(val failure: NetworkConfigurationFailure) : NetworkBootstrapFailure

    data class Trust(val failure: DerivedTrustStoreFailure) : NetworkBootstrapFailure

    data object BoundaryUnavailable : NetworkBootstrapFailure
}

const val DAEMON_PREFIX =
    io.github.amichne.kast.distribution.contract.network.KastNetworkPropertyNamespace.DAEMON_PREFIX

/** Resolves two consumers before any Gradle networking, retaining original configuration precedence. */
object InstalledNetworkBootstrap {
    val environmentKeys =
        setOf("KAST_NETWORK_CONFIG", "KAST_TRUST_DONOR_JAVA_HOME", "KAST_IDE_CONFIG_HOME", "GRADLE_USER_HOME")

    fun prepare(
        root: Path,
        cache: Path,
        targetJavaHome: Path,
        environment: Map<String, String>,
        consumer: NetworkConsumer = NetworkConsumer.SIDECAR_TOOLING_CLIENT,
    ): NetworkBootstrapResult {
        return try {
            val userHome =
                Path.of(
                    environment["GRADLE_USER_HOME"] ?: Path.of(System.getProperty("user.home"), ".gradle").toString()
                )
            val gradle =
                when (val read = GradleNetworkConfiguration.read(root, userHome)) {
                    is Refinement.Refined -> read.value
                    is Refinement.Rejected -> return rejected(read.failure)
                }
            val kast =
                when (val read = explicit(environment["KAST_NETWORK_CONFIG"])) {
                    is Refinement.Refined -> read.value
                    is Refinement.Rejected -> return rejected(read.failure)
                }
            val networkDirectory =
                cache.resolve(if (consumer == NetworkConsumer.SIDECAR_TOOLING_CLIENT) "network" else "network-daemon")
            if (gradle.trust is TrustSelection.Explicit) {
                val trust = gradle.trust as TrustSelection.Explicit
                if (!Files.isRegularFile(trust.path))
                    return NetworkBootstrapResult.Rejected(
                        NetworkBootstrapFailure.Trust(DerivedTrustStoreFailure.DONOR_UNAVAILABLE)
                    )
                privateReceipt(networkDirectory, "explicit-gradle", setOf(TrustProvenance.EXPLICIT_GRADLE))
                return NetworkBootstrapResult.Prepared(
                    kast.fallbackBehind(gradle),
                    kast.missingFrom(gradle),
                    TrustProvenance.EXPLICIT_GRADLE,
                )
            }
            val donors = mutableListOf<TrustStoreDonor>()
            if (kast.trust == TrustSelection.RuntimeDefault) {
                environment["KAST_TRUST_DONOR_JAVA_HOME"]?.let { home ->
                    val path =
                        canonicalDirectory(home)
                            ?: return NetworkBootstrapResult.Rejected(NetworkBootstrapFailure.BoundaryUnavailable)
                    donors += TrustStoreDonor(jvmStore(path), TrustProvenance.DONOR_JVM)
                }
                environment["KAST_IDE_CONFIG_HOME"]?.let { home ->
                    val path =
                        canonicalDirectory(home)
                            ?: return NetworkBootstrapResult.Rejected(NetworkBootstrapFailure.BoundaryUnavailable)
                    val store = path.resolve("ssl/cacerts")
                    if (Files.exists(store)) donors += TrustStoreDonor(store, TrustProvenance.INTELLIJ_ACCEPTED)
                }
            }
            val source: TrustProvenance
            val fallback: NetworkConfiguration
            when (val trust = kast.trust) {
                is TrustSelection.Explicit -> {
                    // Explicit Kast trust is validated before crossing into either consumer.
                    donors.clear()
                    donors +=
                        TrustStoreDonor(
                            trust.path,
                            TrustProvenance.EXPLICIT_KAST,
                            trust.type,
                            trust.provider,
                            trust.password,
                        )
                    source = TrustProvenance.EXPLICIT_KAST
                }
                TrustSelection.RuntimeDefault -> {
                    source = donors.firstOrNull()?.provenance ?: TrustProvenance.TARGET_JVM
                    if (donors.isNotEmpty())
                        donors += TrustStoreDonor(jvmStore(targetJavaHome), TrustProvenance.TARGET_JVM)
                }
            }
            if (donors.isEmpty()) {
                fallback = kast
                privateReceipt(networkDirectory, "target-jvm", emptySet())
            } else {
                when (val materialized = DerivedTrustStoreMaterializer.materialize(networkDirectory, donors)) {
                    is DerivedTrustStoreMaterialization.Rejected ->
                        return NetworkBootstrapResult.Rejected(NetworkBootstrapFailure.Trust(materialized.failure))
                    is DerivedTrustStoreMaterialization.Complete -> {
                        fallback =
                            when (val configured = kast.withDerivedTrust(materialized.path)) {
                                is Refinement.Refined -> configured.value
                                is Refinement.Rejected -> return rejected(configured.failure)
                            }
                        privateReceipt(networkDirectory, materialized.digest, materialized.sources)
                    }
                }
            }
            // Gradle configuration is consumer-owned. It also supplies Tooling API distribution trust
            // when explicitly declared, without overwriting it with discovered enterprise material.
            val sidecar = fallback.fallbackBehind(gradle)
            NetworkBootstrapResult.Prepared(sidecar, fallback.missingFrom(gradle), source)
        } catch (_: IOException) {
            NetworkBootstrapResult.Rejected(NetworkBootstrapFailure.BoundaryUnavailable)
        } catch (_: IllegalArgumentException) {
            NetworkBootstrapResult.Rejected(NetworkBootstrapFailure.BoundaryUnavailable)
        } catch (_: SecurityException) {
            NetworkBootstrapResult.Rejected(NetworkBootstrapFailure.BoundaryUnavailable)
        }
    }

    /** Passive admission reports authority only; it does not materialize or install a policy. */
    fun observeConfiguration(
        root: Path,
        environment: Map<String, String>,
    ): Refinement<TrustProvenance, NetworkConfigurationFailure> =
        try {
            val userHome =
                Path.of(
                    environment["GRADLE_USER_HOME"] ?: Path.of(System.getProperty("user.home"), ".gradle").toString()
                )
            val gradle =
                when (val read = GradleNetworkConfiguration.read(root, userHome)) {
                    is Refinement.Refined -> read.value
                    is Refinement.Rejected -> return read
                }
            val kast =
                when (val read = explicit(environment["KAST_NETWORK_CONFIG"])) {
                    is Refinement.Refined -> read.value
                    is Refinement.Rejected -> return read
                }
            Refinement.Refined(
                when {
                    gradle.trust is TrustSelection.Explicit -> TrustProvenance.EXPLICIT_GRADLE
                    kast.trust is TrustSelection.Explicit -> TrustProvenance.EXPLICIT_KAST
                    "KAST_TRUST_DONOR_JAVA_HOME" in environment -> TrustProvenance.DONOR_JVM
                    "KAST_IDE_CONFIG_HOME" in environment -> TrustProvenance.INTELLIJ_ACCEPTED
                    else -> TrustProvenance.TARGET_JVM
                }
            )
        } catch (_: IOException) {
            Refinement.Rejected(NetworkConfigurationFailure.CONFIGURATION_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            Refinement.Rejected(NetworkConfigurationFailure.INVALID_PATH)
        } catch (_: SecurityException) {
            Refinement.Rejected(NetworkConfigurationFailure.CONFIGURATION_UNAVAILABLE)
        }

    private fun explicit(file: String?): Refinement<NetworkConfiguration, NetworkConfigurationFailure> {
        if (file == null) return Refinement.Refined(NetworkConfiguration.Empty)
        val path = Path.of(file)
        if (!path.isAbsolute || !Files.isRegularFile(path) || Files.size(path) > 65_536) {
            return Refinement.Rejected(NetworkConfigurationFailure.CONFIGURATION_UNAVAILABLE)
        }
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        return NetworkConfiguration.parse(properties.stringPropertyNames().associateWith(properties::getProperty))
    }

    private fun jvmStore(home: Path): Path {
        // Java 8 JDK homes contain a nested JRE. A Java 8 JRE home and Java 9+ homes
        // already name the runtime. Choose that layout before applying JSSE precedence.
        val nestedSecurity = home.resolve("jre/lib/security")
        val security = if (Files.isDirectory(nestedSecurity)) nestedSecurity else home.resolve("lib/security")
        val overrideStore = security.resolve("jssecacerts")
        // An existing but invalid override must fail at donor admission, not fall through.
        return if (Files.exists(overrideStore, java.nio.file.LinkOption.NOFOLLOW_LINKS)) overrideStore
        else security.resolve("cacerts")
    }

    private fun canonicalDirectory(raw: String): Path? {
        val path = Path.of(raw)
        return if (path.isAbsolute && Files.isDirectory(path)) path.toRealPath() else null
    }

    private fun rejected(failure: NetworkConfigurationFailure) =
        NetworkBootstrapResult.Rejected(NetworkBootstrapFailure.Configuration(failure))

    private fun privateReceipt(directory: Path, digest: String, sources: Set<TrustProvenance>) {
        privateNetworkDirectory(directory)
        val posix = Files.getFileStore(directory).supportsFileAttributeView("posix")
        val attributes =
            if (posix) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            else emptyArray()
        val temporary = Files.createTempFile(directory, ".policy-", ".tmp", *attributes)
        try {
            Files.writeString(
                temporary,
                "digest=$digest\nsources=${sources.sortedBy { it.name }.joinToString(",") { it.name }}\n",
            )
            Files.move(
                temporary,
                directory.resolve("policy.properties"),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
