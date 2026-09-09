package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.host.admission.CodexAppServerArguments
import io.github.amichne.kast.appserver.host.admission.DesktopFacadeExecutables
import io.github.amichne.kast.appserver.host.admission.UpstreamCodexExecutable
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationChild
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOwner
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal const val VENDORED_BROKER_VERSION = "0.7.0"

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
    SOCKET_PATH_REJECTED,
    PROVIDER_CONFIGURATION_REJECTED,
    PROTOCOL_CONFIGURATION_REJECTED,
    LAUNCHCTL_TIMED_OUT,
    STARTUP_TIMED_OUT,
    INTERRUPTED,
    DISABLED,
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

@JvmInline
internal value class BrokerServiceIdentity private constructor(val value: String) {
    companion object {
        internal fun derive(
            kastDigest: String,
            host: BrokerHostSelection,
            kast: Path,
            userHome: Path,
            javaHome: Path,
            javaExecutable: Path,
            codexHome: Path,
            executableSearchPath: BrokerExecutableSearchPath,
            toolSelection: KastToolSelection,
            childEnvironment: BrokerChildEnvironment,
        ): BrokerServiceIdentity {
            val source = listOf(
                VENDORED_BROKER_VERSION,
                CodexAppServerArguments.sharedService().withOwnedTransport("unix://").joinToString("\n"),
                kastDigest,
                host.identityValue,
                kast,
                userHome,
                javaHome,
                javaExecutable,
                codexHome,
                executableSearchPath.value,
                toolSelection.environmentValue,
                childEnvironment.identityValue,
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

/** Exact, bounded executable search path required by the admitted launch commands. */
@JvmInline
internal value class BrokerExecutableSearchPath private constructor(val value: String) {
    companion object {
        internal fun coordinator(kast: Path): BrokerExecutableSearchPath? = derive(kast.parent, kast, kast)

        internal fun derive(
            codexLauncherDirectory: Path,
            codex: Path,
            kast: Path,
        ): BrokerExecutableSearchPath? {
            val directories = listOf(
                codexLauncherDirectory,
                codex.parent,
                kast.parent,
                Path.of("/usr/bin"),
                Path.of("/bin"),
                Path.of("/usr/sbin"),
                Path.of("/sbin"),
            ).distinct()
            if (
                directories.any { directory ->
                    val raw = directory.toString()
                    !directory.isAbsolute || directory.normalize() != directory ||
                        raw.isBlank() || raw.any(Char::isISOControl) ||
                        File.pathSeparatorChar in raw
                }
            ) {
                return null
            }
            val value = directories.joinToString(File.pathSeparator) { directory ->
                directory.toString()
            }
            return value.takeIf { it.length <= MAXIMUM_EXECUTABLE_PATH_CHARACTERS }
                ?.let(::BrokerExecutableSearchPath)
        }

        private const val MAXIMUM_EXECUTABLE_PATH_CHARACTERS = 32 * 1_024
    }
}

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

internal enum class BrokerChildEnvironmentFailure { INVALID_VALUE }

/** Exact admitted runtime configuration forwarded across launchd's empty environment. */
internal class BrokerChildEnvironment private constructor(
    val assignments: List<String>,
    val identityValue: String,
) {

    companion object {
        internal fun admit(
            environment: Map<String, String>,
        ): Refinement<BrokerChildEnvironment, BrokerChildEnvironmentFailure> {
            val configuration = when (val resolution = ResolvedKastConfiguration.resolve(ConfigurationSources(environment))) {
                is Refinement.Refined -> resolution.value
                is Refinement.Rejected -> return Refinement.Rejected(BrokerChildEnvironmentFailure.INVALID_VALUE)
            }
            return admit(configuration)
        }

        internal fun admit(configuration: ResolvedKastConfiguration): Refinement<BrokerChildEnvironment, BrokerChildEnvironmentFailure> {
            val assignments = configuration.childEnvironment(ConfigurationChild.BROKER).toSortedMap().map { (name, value) ->
                if (value.length > MAXIMUM_CHILD_ENVIRONMENT_VALUE_CHARACTERS ||
                    value.any { it == '\n' || it == '\r' || it == '\u0000' }) {
                    return Refinement.Rejected(BrokerChildEnvironmentFailure.INVALID_VALUE)
                }
                "$name=$value"
            }
            val identity = configuration.childIdentityInputs(ConfigurationChild.BROKER).toSortedMap()
                .entries.joinToString("\n") { (key, value) -> "$key=$value" }
            return Refinement.Refined(BrokerChildEnvironment(assignments, identity))
        }

        private const val MAXIMUM_CHILD_ENVIRONMENT_VALUE_CHARACTERS = 32 * 1_024

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

internal sealed interface BrokerHostSelection {
    class Selected(val executable: UpstreamCodexExecutable, val launcherDirectory: Path, private val payloadDigest: String) : BrokerHostSelection {
        override val identityValue: String = "selected\n${executable.path}\n$payloadDigest"
    }
    data object Disabled : BrokerHostSelection { override val identityValue = "disabled" }
    data object NotConfigured : BrokerHostSelection { override val identityValue = "not-configured" }
    val identityValue: String
    fun environment(): Map<String, String> = when (this) {
        is Selected -> mapOf("CODEX_EXECUTABLE" to executable.launcherPath.toString())
        Disabled, NotConfigured -> emptyMap()
    }
}

internal enum class BrokerServicePurpose { HOST_ATTACHMENT, COORDINATOR }

internal class BrokerServiceLaunchCommand private constructor(
    val host: BrokerHostSelection,
    val kast: Path,
    val executableSearchPath: BrokerExecutableSearchPath,
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
    val toolSelection: KastToolSelection,
    val childEnvironment: BrokerChildEnvironment,
    val configuration: ResolvedKastConfiguration,
) {
    companion object {
        fun resolveCoordinator(kastCandidate: Path, userHomeCandidate: Path, environment: Map<String, String>): BrokerServiceLaunchCommandResolution =
            resolve(kastCandidate, userHomeCandidate, environment, purpose = BrokerServicePurpose.COORDINATOR)

        fun resolve(
            kastCandidate: Path,
            userHomeCandidate: Path,
            environment: Map<String, String>,
            javaHomeCandidate: Path = Path.of(System.getProperty("java.home")),
            purpose: BrokerServicePurpose = BrokerServicePurpose.HOST_ATTACHMENT,
        ): BrokerServiceLaunchCommandResolution {
            val kast = regularExecutable(kastCandidate, BrokerSymbolicLinkPolicy.EXACT_PATH)
                ?: return rejected(PersistentBrokerServiceFailure.KAST_EXECUTABLE_UNAVAILABLE)
            val userHome = canonicalDirectory(userHomeCandidate)
                ?: return rejected(PersistentBrokerServiceFailure.USER_HOME_REJECTED)
            val jvmUserHomeOption = BrokerJvmUserHomeOption.from(userHome)
                ?: return rejected(PersistentBrokerServiceFailure.USER_HOME_REJECTED)
            val admittedConfiguration = when (val admission = InstalledBrokerConfigurationIngress.admit(environment)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return rejected(when (val failure = admission.failure) {
                    is BrokerConfigurationIngressRejection.Configuration -> when (failure.failure.key) {
                        "CODEX_EXECUTABLE", "KAST_REAL_CODEX_EXECUTABLE" -> PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE
                        "CODEX_HOME" -> PersistentBrokerServiceFailure.CODEX_HOME_REJECTED
                        else -> PersistentBrokerServiceFailure.CONFIGURATION_REJECTED
                    }
                    is BrokerConfigurationIngressRejection.Owner, is BrokerConfigurationIngressRejection.Source -> PersistentBrokerServiceFailure.CONFIGURATION_REJECTED
                })
            }
            val configuration = admittedConfiguration.configuration
            val ownerInputs = configuration.ownerInputs(ConfigurationOwner.APP_SERVER)
            if (purpose == BrokerServicePurpose.HOST_ATTACHMENT && admittedConfiguration.toolingMode == AppServerToolingMode.DISABLED) {
                return rejected(PersistentBrokerServiceFailure.DISABLED)
            }
            val toolSelection = admittedConfiguration.toolSelection
            val javaHome = canonicalDirectoryTarget(javaHomeCandidate)
                ?: return rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE)
            val javaExecutable = regularExecutable(
                javaHome.resolve("bin/java"),
                BrokerSymbolicLinkPolicy.CANONICAL_TARGET,
            ) ?: return rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE)
            val searchPath = environment["PATH"].orEmpty()
            val codexSelection = if (ownerInputs.containsKey("CODEX_EXECUTABLE")) {
                absoluteExecutableSelection(ownerInputs.getValue("CODEX_EXECUTABLE"))
                    ?: return rejected(PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE)
            } else resolveExecutable("codex", searchPath)
            val host = when {
                admittedConfiguration.toolingMode == AppServerToolingMode.DISABLED -> BrokerHostSelection.Disabled
                codexSelection == null -> {
                    if (purpose == BrokerServicePurpose.HOST_ATTACHMENT) return rejected(PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE)
                    BrokerHostSelection.NotConfigured
                }
                else -> {
                    val facades = DesktopFacadeExecutables.resolve(kast.parent.resolve("kast-codex"), null)
                    val codex = when (val admission = UpstreamCodexExecutable.admit(codexSelection.executable,
                        facades, launcherCandidate = codexSelection.launcher)) {
                        is Refinement.Refined -> admission.value
                        is Refinement.Rejected -> return rejected(PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE)
                    }
                    val digest = sha256(codex.path) ?: return rejected(PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE)
                    BrokerHostSelection.Selected(codex, codexSelection.launcher.parent, digest)
                }
            }
            val executableSearchPath = when (host) {
                is BrokerHostSelection.Selected -> BrokerExecutableSearchPath.derive(
                    host.launcherDirectory, host.executable.path, kast)
                BrokerHostSelection.Disabled, BrokerHostSelection.NotConfigured -> BrokerExecutableSearchPath.coordinator(kast)
            } ?: return rejected(PersistentBrokerServiceFailure.CONFIGURATION_REJECTED)
            val codexHome = if (ownerInputs.containsKey("CODEX_HOME")) {
                absoluteNormalizedPath(ownerInputs.getValue("CODEX_HOME"))
            } else {
                userHome.resolve(".codex")
            } ?: return rejected(PersistentBrokerServiceFailure.CODEX_HOME_REJECTED)
            val childEnvironment = when (val admission = BrokerChildEnvironment.admit(configuration)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return rejected(
                    PersistentBrokerServiceFailure.CONFIGURATION_REJECTED,
                )
            }
            val installation = BrokerInstallationLayout.from(kast, codexHome)
            val stateDirectory = installation.broker
            val kastDigest = sha256(kast)
                ?: return rejected(PersistentBrokerServiceFailure.KAST_EXECUTABLE_UNAVAILABLE)
            val identity = BrokerServiceIdentity.derive(
                kastDigest,
                host,
                kast,
                userHome,
                javaHome,
                javaExecutable,
                codexHome,
                executableSearchPath,
                toolSelection,
                childEnvironment,
            )
            return BrokerServiceLaunchCommandResolution.Resolved(
                BrokerServiceLaunchCommand(
                    host,
                    kast,
                    executableSearchPath,
                    userHome,
                    javaHome,
                    javaExecutable,
                    jvmUserHomeOption,
                    codexHome,
                    stateDirectory,
                    stateDirectory.resolve("service-readiness.json"),
                    installation.publicSocket,
                    stateDirectory.resolve("service.log"),
                    stateDirectory.resolve("service-start.lock"),
                    identity,
                    BrokerLaunchdServiceLabel.from(installation.root),
                    toolSelection,
                    childEnvironment,
                    configuration,
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

        private fun resolveExecutable(name: String, searchPath: String): BrokerCommandExecutable? {
            val rawDirectories = searchPath.split(File.pathSeparatorChar)
            if (rawDirectories.isEmpty() || rawDirectories.any(String::isBlank)) return null
            return rawDirectories.asSequence()
                .mapNotNull(::absoluteNormalizedPath)
                .mapNotNull { directory ->
                    val launcherDirectory = canonicalDirectoryTarget(directory)
                        ?: return@mapNotNull null
                    val executable = regularExecutable(
                        launcherDirectory.resolve(name),
                        BrokerSymbolicLinkPolicy.CANONICAL_TARGET,
                    ) ?: return@mapNotNull null
                    BrokerCommandExecutable(executable, launcherDirectory.resolve(name))
                }
                .firstOrNull()
        }

        private fun absoluteExecutableSelection(raw: String): BrokerCommandExecutable? =
            absoluteNormalizedPath(raw)?.let { candidate ->
                val executable = regularExecutable(
                    candidate,
                    BrokerSymbolicLinkPolicy.CANONICAL_TARGET,
                ) ?: return@let null
                val launcherDirectory = canonicalDirectoryTarget(candidate.parent)
                    ?: return@let null
                BrokerCommandExecutable(executable, launcherDirectory.resolve(candidate.fileName))
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

private data class BrokerCommandExecutable(
    val executable: Path,
    val launcher: Path,
)

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
        val resolution = BrokerServiceLaunchCommand.resolveCoordinator(kast, userHome, environment)
    ) {
        is BrokerServiceLaunchCommandResolution.Resolved -> host.ensure(resolution.command)
        is BrokerServiceLaunchCommandResolution.Rejected ->
            PersistentBrokerServiceAdmission.Rejected(resolution.failure)
    }
}
