package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.cli.HostedRuntimeDemand
import io.github.amichne.kast.cli.RootRuntimeDemander
import io.github.amichne.kast.cli.RuntimeAdmission
import io.github.amichne.kast.cli.RuntimeAdmissionFailure
import io.github.amichne.kast.cli.RuntimeStartupRequest
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal const val VENDORED_BROKER_VERSION = "0.5.0"

enum class PersistentBrokerServiceFailure {
    UNAVAILABLE,
    CONFIGURATION_REJECTED,
    KAST_QUALIFICATION_REJECTED,
    CATALOG_REJECTED,
    CODEX_QUALIFICATION_REJECTED,
    THREAD_STORE_REJECTED,
    UPSTREAM_REJECTED,
    SERVER_REJECTED,
    KAST_EXECUTABLE_UNAVAILABLE,
    CODEX_EXECUTABLE_UNAVAILABLE,
    CODEX_HOME_REJECTED,
    USER_HOME_REJECTED,
    JAVA_RUNTIME_UNAVAILABLE,
    STATE_DIRECTORY_REJECTED,
    SERVICE_LOCK_REJECTED,
    SERVICE_OBSERVATION_REJECTED,
    SERVICE_RETIREMENT_REJECTED,
    SERVICE_SUBMISSION_REJECTED,
    READINESS_REJECTED,
    PUBLIC_SOCKET_OWNED,
    SOCKET_PROBE_REJECTED,
    LAUNCHCTL_TIMED_OUT,
    STARTUP_TIMED_OUT,
    INTERRUPTED,
}

internal sealed interface PersistentBrokerServiceAdmission {
    data object Ready : PersistentBrokerServiceAdmission

    data class Rejected(
        val failure: PersistentBrokerServiceFailure,
    ) : PersistentBrokerServiceAdmission
}

internal fun interface PersistentBrokerService {
    fun ensure(): PersistentBrokerServiceAdmission
}

internal class BrokerEnsuringRootRuntimeDemander(
    private val broker: PersistentBrokerService,
    private val delegate: RootRuntimeDemander,
) : RootRuntimeDemander {
    override fun demand(
        root: CanonicalRoot,
        demand: HostedRuntimeDemand,
        startup: RuntimeStartupRequest,
    ): RuntimeAdmission = when (val admission = broker.ensure()) {
        PersistentBrokerServiceAdmission.Ready -> delegate.demand(root, demand, startup)
        is PersistentBrokerServiceAdmission.Rejected -> RuntimeAdmission.Rejected(
            RuntimeAdmissionFailure.PersistentBroker(admission.failure),
        )
    }
}

@JvmInline
internal value class BrokerServiceIdentity private constructor(val value: String) {
    companion object {
        internal fun derive(
            kastDigest: String,
            codexDigest: String,
            codex: Path,
            codexInvocationDirectory: BrokerExecutableInvocationDirectory,
            kast: Path,
            userHome: Path,
            javaHome: Path,
            javaExecutable: Path,
            codexHome: Path,
        ): BrokerServiceIdentity {
            val source = listOf(
                VENDORED_BROKER_VERSION,
                kastDigest,
                codexDigest,
                codex,
                codexInvocationDirectory.path,
                kast,
                userHome,
                javaHome,
                javaExecutable,
                codexHome,
            ).joinToString("\n")
            val digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(source.toByteArray(StandardCharsets.UTF_8)),
            )
            return BrokerServiceIdentity("sha256:$digest")
        }

        internal fun admit(raw: String): BrokerServiceIdentity? =
            raw.takeIf(IDENTITY::matches)?.let(::BrokerServiceIdentity)

        private val IDENTITY = Regex("sha256:[0-9a-f]{64}")
    }
}

@JvmInline
internal value class BrokerExecutableInvocationDirectory private constructor(val path: Path) {
    companion object {
        internal fun admit(candidate: Path): BrokerExecutableInvocationDirectory? {
            if (!candidate.isAbsolute || candidate.normalize() != candidate) return null
            val canonical = try {
                candidate.toRealPath()
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
            return canonical
                .takeIf { directory -> Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) }
                ?.let(::BrokerExecutableInvocationDirectory)
        }
    }
}

private data class ResolvedBrokerExecutable(
    val physicalPath: Path,
    val invocationDirectory: BrokerExecutableInvocationDirectory,
)

@JvmInline
internal value class BrokerJvmUserHomeOption private constructor(val value: String) {
    companion object {
        internal fun from(userHome: Path): BrokerJvmUserHomeOption? {
            val raw = userHome.toString()
            if (raw.any { character -> character == '\n' || character == '\r' || character == '\u0000' }) {
                return null
            }
            val quoted = raw.replace("\\", "\\\\").replace("\"", "\\\"")
            return BrokerJvmUserHomeOption("-Duser.home=\"$quoted\"")
        }
    }
}

@JvmInline
internal value class BrokerLaunchdServiceLabel private constructor(val value: String) {
    companion object {
        internal fun from(codexHome: Path): BrokerLaunchdServiceLabel {
            val digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    codexHome.toString().toByteArray(StandardCharsets.UTF_8),
                ),
            )
            return BrokerLaunchdServiceLabel(
                "io.github.amichne.kast.broker.${digest.take(32)}",
            )
        }
    }
}

internal sealed interface BrokerServiceLaunchCommandResolution {
    data class Resolved(val command: BrokerServiceLaunchCommand) :
        BrokerServiceLaunchCommandResolution

    data class Rejected(val failure: PersistentBrokerServiceFailure) :
        BrokerServiceLaunchCommandResolution
}

internal class BrokerServiceLaunchCommand private constructor(
    val codex: Path,
    val codexInvocationDirectory: BrokerExecutableInvocationDirectory,
    val kast: Path,
    val userHome: Path,
    val javaHome: Path,
    val javaExecutable: Path,
    val jvmUserHomeOption: BrokerJvmUserHomeOption,
    val codexHome: Path,
    val stateDirectory: Path,
    val readinessFile: Path,
    val publicSocket: Path,
    val serviceLog: Path,
    val serviceLock: Path,
    val identity: BrokerServiceIdentity,
    val serviceLabel: BrokerLaunchdServiceLabel,
) {
    companion object {
        fun resolve(
            kastCandidate: Path,
            userHomeCandidate: Path,
            environment: Map<String, String>,
            javaHomeCandidate: Path = Path.of(System.getProperty("java.home")),
        ): BrokerServiceLaunchCommandResolution {
            val kast = regularExecutable(kastCandidate, BrokerSymbolicLinkPolicy.EXACT_PATH)
                ?: return rejected(PersistentBrokerServiceFailure.KAST_EXECUTABLE_UNAVAILABLE)
            val userHome = canonicalDirectory(userHomeCandidate)
                ?: return rejected(PersistentBrokerServiceFailure.USER_HOME_REJECTED)
            val jvmUserHomeOption = BrokerJvmUserHomeOption.from(userHome)
                ?: return rejected(PersistentBrokerServiceFailure.USER_HOME_REJECTED)
            val javaHome = canonicalDirectoryTarget(javaHomeCandidate)
                ?: return rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE)
            val javaExecutable = regularExecutable(
                javaHome.resolve("bin/java"),
                BrokerSymbolicLinkPolicy.CANONICAL_TARGET,
            ) ?: return rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE)
            val searchPath = environment["PATH"].orEmpty()
            val codex = if (environment.containsKey("CODEX_EXECUTABLE")) {
                absoluteExecutable(environment.getValue("CODEX_EXECUTABLE"))
            } else {
                resolveExecutable("codex", searchPath)
            } ?: return rejected(PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE)
            val codexHome = if (environment.containsKey("CODEX_HOME")) {
                absoluteNormalizedPath(environment.getValue("CODEX_HOME"))
            } else {
                userHome.resolve(".codex")
            } ?: return rejected(PersistentBrokerServiceFailure.CODEX_HOME_REJECTED)
            val stateDirectory = codexHome.resolve("broker")
            val kastDigest = sha256(kast)
                ?: return rejected(PersistentBrokerServiceFailure.KAST_EXECUTABLE_UNAVAILABLE)
            val codexDigest = sha256(codex.physicalPath)
                ?: return rejected(PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE)
            val identity = BrokerServiceIdentity.derive(
                kastDigest,
                codexDigest,
                codex.physicalPath,
                codex.invocationDirectory,
                kast,
                userHome,
                javaHome,
                javaExecutable,
                codexHome,
            )
            return BrokerServiceLaunchCommandResolution.Resolved(
                BrokerServiceLaunchCommand(
                    codex.physicalPath,
                    codex.invocationDirectory,
                    kast,
                    userHome,
                    javaHome,
                    javaExecutable,
                    jvmUserHomeOption,
                    codexHome,
                    stateDirectory,
                    stateDirectory.resolve("service-readiness.json"),
                    codexHome.resolve("app-server-control/app-server-control.sock"),
                    stateDirectory.resolve("service.log"),
                    stateDirectory.resolve("service-start.lock"),
                    identity,
                    BrokerLaunchdServiceLabel.from(codexHome),
                ),
            )
        }

        private fun regularExecutable(
            candidate: Path,
            symbolicLinkPolicy: BrokerSymbolicLinkPolicy,
        ): Path? {
            if (!candidate.isAbsolute || candidate.normalize() != candidate) return null
            if (
                symbolicLinkPolicy == BrokerSymbolicLinkPolicy.EXACT_PATH &&
                Files.isSymbolicLink(candidate)
            ) return null
            val canonical = try {
                candidate.toRealPath()
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
            if (!Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)) return null
            return canonical.takeIf(Files::isExecutable)
        }

        private fun canonicalDirectory(candidate: Path): Path? = try {
            candidate.toRealPath().takeIf { path ->
                path == candidate && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        private fun canonicalDirectoryTarget(candidate: Path): Path? = try {
            candidate.toRealPath().takeIf { path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        private fun resolveExecutable(name: String, searchPath: String): ResolvedBrokerExecutable? {
            val rawDirectories = searchPath.split(File.pathSeparatorChar)
            if (rawDirectories.isEmpty() || rawDirectories.any(String::isBlank)) return null
            return rawDirectories.asSequence()
                .mapNotNull(::absoluteNormalizedPath)
                .map { directory -> directory.resolve(name) }
                .mapNotNull(::resolvedExecutable)
                .firstOrNull()
        }

        private fun absoluteExecutable(raw: String): ResolvedBrokerExecutable? =
            absoluteNormalizedPath(raw)?.let(::resolvedExecutable)

        private fun resolvedExecutable(candidate: Path): ResolvedBrokerExecutable? {
            val physicalPath = regularExecutable(
                candidate,
                BrokerSymbolicLinkPolicy.CANONICAL_TARGET,
            ) ?: return null
            val parent = candidate.parent ?: return null
            val invocationDirectory = BrokerExecutableInvocationDirectory.admit(parent) ?: return null
            return ResolvedBrokerExecutable(physicalPath, invocationDirectory)
        }

        private fun absoluteNormalizedPath(raw: String): Path? = try {
            Path.of(raw).takeIf { path -> path.isAbsolute && path.normalize() == path }
        } catch (_: RuntimeException) {
            null
        }

        private fun sha256(path: Path): String? = try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            "sha256:${HexFormat.of().formatHex(digest.digest())}"
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        private fun rejected(
            failure: PersistentBrokerServiceFailure,
        ): BrokerServiceLaunchCommandResolution.Rejected =
            BrokerServiceLaunchCommandResolution.Rejected(failure)
    }
}

private enum class BrokerSymbolicLinkPolicy { EXACT_PATH, CANONICAL_TARGET }

internal fun interface PersistentBrokerServiceHost {
    fun ensure(command: BrokerServiceLaunchCommand): PersistentBrokerServiceAdmission
}

internal class InstalledPersistentBrokerService(
    private val kast: Path,
    private val userHome: Path,
    private val environment: Map<String, String> = System.getenv(),
    private val host: PersistentBrokerServiceHost = MacOsPersistentBrokerServiceHost(),
) : PersistentBrokerService {
    override fun ensure(): PersistentBrokerServiceAdmission = when (
        val resolution = BrokerServiceLaunchCommand.resolve(kast, userHome, environment)
    ) {
        is BrokerServiceLaunchCommandResolution.Resolved -> host.ensure(resolution.command)
        is BrokerServiceLaunchCommandResolution.Rejected ->
            PersistentBrokerServiceAdmission.Rejected(resolution.failure)
    }
}
