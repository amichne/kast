package io.github.amichne.kast.distribution.contract

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The only Kotlin derivation of installation-relative paths.
 *
 * The installation directory is `<management root>/installation`. Every accessor derives its path on access, so
 * admitting a root performs no filesystem effect and no eager failure. An architecture rule rejects new
 * installation-relative path literals outside this owner, so a later layout change edits this file alone.
 */
class InstallationLayout private constructor(
    /** The installation directory. */
    val root: Path,
) {
    /** The management root that contains the installation directory and its lifecycle records. */
    val managementRoot: Path
        get() = root.parent

    /** Client configuration beneath `config`. */
    val configuration: Configuration
        get() = Configuration(root.resolve(CONFIGURATION_DIRECTORY))

    /** Coordinator state beneath `state`. */
    val state: State
        get() = State(root.resolve(STATE_DIRECTORY))

    /** Files the release payload delivers into the installation directory. */
    val payload: Payload
        get() = Payload(root)

    /** Lifecycle records and stores beside the installation directory. */
    val management: Management
        get() = Management(managementRoot)

    override fun equals(other: Any?): Boolean = other is InstallationLayout && other.root == root

    override fun hashCode(): Int = root.hashCode()

    override fun toString(): String = "InstallationLayout($root)"

    /** `config`: the client configuration file, workspace overlays, the workspace registry, and the selected IDE. */
    class Configuration internal constructor(val directory: Path) {
        val environment: Path
            get() = directory.resolve(ENVIRONMENT_FILE)

        val workspaceRegistry: Path
            get() = directory.resolve("workspaces.json")

        val selectedIde: Path
            get() = directory.resolve("selected-ide.json")

        /** The saved overlay for one workspace, keyed by the SHA-256 of the workspace root path. */
        fun workspaceEnvironment(workspaceRoot: Path): Path =
            directory.resolve("workspaces").resolve(WorkspaceOverlayIdentity.of(workspaceRoot).value)
                .resolve(ENVIRONMENT_FILE)
    }

    /** `state`: the installation epoch, per-host coordinator state, and runtime sockets and invocation records. */
    class State internal constructor(val directory: Path) {
        val epoch: Path
            get() = directory.resolve("epoch.json")

        /** The parent of every per-host coordinator state directory. */
        val brokers: Path
            get() = directory.resolve("broker")

        val run: Run
            get() = Run(directory.resolve("run"))

        /** Worker state from an earlier coordinator; read only to retire it. */
        val legacyWorkers: Path
            get() = directory.resolve("workers")

        fun broker(profile: CoordinatorHostProfile): Path = brokers.resolve(profile.value)

        fun readiness(profile: CoordinatorHostProfile): Path = broker(profile).resolve(READINESS_FILE)
    }

    /** `state/run`: runtime sockets and live invocation records. */
    class Run internal constructor(val directory: Path) {
        val coordinatorSocket: Path
            get() = directory.resolve(COORDINATOR_SOCKET_FILE)

        val upstreamSocket: Path
            get() = directory.resolve("u.sock")

        val oneShotInvocations: Path
            get() = directory.resolve("one-shot")

        val toolSessions: Path
            get() = directory.resolve("tool-sessions")
    }

    /** Release payload files inside the installation directory. */
    class Payload internal constructor(private val root: Path) {
        val manifest: Path
            get() = Relative.MANIFEST.under(root)

        val kast: Path
            get() = Relative.KAST_EXECUTABLE.under(root)

        val libraries: Path
            get() = Relative.LIBRARIES.under(root)

        /** `share/kast`: payload resources. */
        val share: Path
            get() = Relative.SHARE.under(root)

        val productSelection: Path
            get() = Relative.PRODUCT_SELECTION.under(root)

        val buildRecord: Path
            get() = Relative.BUILD_RECORD.under(root)

        val providerCatalog: Path
            get() = Relative.PROVIDER_CATALOG.under(root)

        val managementExecutable: Path
            get() = Relative.MANAGEMENT_EXECUTABLE.under(root)

        val serviceExecutable: Path
            get() = Relative.SERVICE_EXECUTABLE.under(root)

        val daemonExecutable: Path
            get() = Relative.DAEMON_EXECUTABLE.under(root)

        val oneShotObservationCapability: Path
            get() = Relative.ONE_SHOT_OBSERVATION_CAPABILITY.under(root)

        val shutdownCapability: Path
            get() = Relative.SHUTDOWN_CAPABILITY.under(root)

        /** One named resource file directly beneath `share/kast`. */
        fun shareResource(fileName: String): Path = share.resolve(fileName)
    }

    /** The management root: lifecycle records and the stores that live beside it. */
    class Management internal constructor(val root: Path) {
        val receipt: Path
            get() = root.resolve("management.json")

        val shutdownFence: Path
            get() = root.resolve(INSTALLATION_SHUTDOWN_FENCE)

        /** The reset marker beside the management root, keyed by a digest of the management root path. */
        val resetFence: Path
            get() = installationResetFence(root)

        val registrationLock: Path
            get() = root.resolve("management.lock")

        val activationLock: Path
            get() = root.resolve("activation.lock")

        val recovery: Path
            get() = root.resolve("recovery")

        val replacementRecovery: Path
            get() = recovery.resolve("replacement")

        val installationRecovery: Path
            get() = recovery.resolve(INSTALLATION_DIRECTORY)

        val productRemoval: Path
            get() = installationRecovery.resolve("product-removal")

        val workspaceStore: Path
            get() = root.resolveSibling("kast-workspaces")

        val runtimeBaseStore: Path
            get() = root.resolveSibling("kast-runtime-bases")
    }

    /**
     * Installation-relative paths in the `/`-separated form that installation manifests, capability checks, and
     * the frontend catalog record. Each absolute payload accessor derives from the same value.
     */
    object Relative {
        val MANIFEST: InstallationRelativePath = InstallationRelativePath.of("installation.json")
        val KAST_EXECUTABLE: InstallationRelativePath = InstallationRelativePath.of(BIN_DIRECTORY, "kast")
        val QUERY_EXECUTABLE: InstallationRelativePath = InstallationRelativePath.of(BIN_DIRECTORY, "kast-query")
        val QUERY_COMPLETE_EXECUTABLE: InstallationRelativePath =
            InstallationRelativePath.of(BIN_DIRECTORY, "kast-query-complete")
        val LIBRARIES: InstallationRelativePath = InstallationRelativePath.of(LIBRARY_DIRECTORY)
        val SHARE: InstallationRelativePath = InstallationRelativePath.of(SHARE_DIRECTORY, PRODUCT_DIRECTORY)
        val PRODUCT_SELECTION: InstallationRelativePath = share("product-selection.json")
        val BUILD_RECORD: InstallationRelativePath = share("build-record.json")
        val PROVIDER_CATALOG: InstallationRelativePath = share("provider-catalog.json")
        val AGENT_TOOLS: InstallationRelativePath = share(AGENT_TOOLS_DIRECTORY)

        /** The bundled CLI skill the agent tools deliver. */
        val CLI_SKILL: InstallationRelativePath =
            InstallationRelativePath.of(
                SHARE_DIRECTORY,
                PRODUCT_DIRECTORY,
                AGENT_TOOLS_DIRECTORY,
                "plugins",
                PRODUCT_DIRECTORY,
                "skills",
                PRODUCT_DIRECTORY,
            )
        val MANAGEMENT_EXECUTABLE: InstallationRelativePath = libexec("kast-management")
        val SERVICE_EXECUTABLE: InstallationRelativePath = libexec("kast-service")
        val DAEMON_EXECUTABLE: InstallationRelativePath = libexec("kast-daemon")
        val ONE_SHOT_OBSERVATION_CAPABILITY: InstallationRelativePath = share("one-shot-observation-v1")
        val SHUTDOWN_CAPABILITY: InstallationRelativePath = share("lifecycle-fence-v1")
        val RESET_CAPABILITY: InstallationRelativePath = share("reset-fence-v1")
        val MANAGED_INSTALL_CAPABILITY: InstallationRelativePath = share("managed-install-v1")
        val INSTALL_CANDIDATE: InstallationRelativePath = share("install-candidate.sh")
        val PRIVATE_PYTHON_INTERPRETER: InstallationRelativePath =
            InstallationRelativePath.of(SHARE_DIRECTORY, PRODUCT_DIRECTORY, "private-python", BIN_DIRECTORY, "python3.12")
        val COORDINATOR_SOCKET: InstallationRelativePath =
            InstallationRelativePath.of(STATE_DIRECTORY, "run", COORDINATOR_SOCKET_FILE)
        val ONE_SHOT_INVOCATIONS: InstallationRelativePath =
            InstallationRelativePath.of(STATE_DIRECTORY, "run", "one-shot")
        val TOOL_SESSIONS: InstallationRelativePath =
            InstallationRelativePath.of(STATE_DIRECTORY, "run", "tool-sessions")

        /** One archive directly beneath `lib`. */
        fun library(fileName: String): InstallationRelativePath =
            InstallationRelativePath.of(LIBRARY_DIRECTORY, fileName)

        private fun share(name: String): InstallationRelativePath =
            InstallationRelativePath.of(SHARE_DIRECTORY, PRODUCT_DIRECTORY, name)

        private fun libexec(name: String): InstallationRelativePath =
            InstallationRelativePath.of(SHARE_DIRECTORY, PRODUCT_DIRECTORY, "libexec", name)
    }

    companion object {
        private const val CONFIGURATION_DIRECTORY = "config"
        private const val STATE_DIRECTORY = "state"
        private const val SHARE_DIRECTORY = "share"
        private const val BIN_DIRECTORY = "bin"
        private const val LIBRARY_DIRECTORY = "lib"
        private const val AGENT_TOOLS_DIRECTORY = "agent-tools"
        private const val PRODUCT_DIRECTORY = "kast"
        private const val INSTALLATION_DIRECTORY = "installation"
        private const val ENVIRONMENT_FILE = "environment"
        private const val READINESS_FILE = "service-readiness.json"
        private const val COORDINATOR_SOCKET_FILE = "c.sock"

        /** The installation directory itself, as admitted by the caller. */
        fun of(installation: Path): InstallationLayout = InstallationLayout(installation)

        /** The installation directory beneath an admitted management root. */
        fun beneath(managementRoot: Path): InstallationLayout =
            InstallationLayout(managementRoot.resolve(INSTALLATION_DIRECTORY))

        /** The installation that contains a `bin/kast` executable. */
        fun ofKastExecutable(kast: Path): InstallationLayout = InstallationLayout(kast.parent.parent)

        /** The installation whose `config/environment` is [configurationFile], or null when it has no such parent. */
        fun ofConfigurationFile(configurationFile: Path): InstallationLayout? =
            configurationFile.parent?.parent?.let(::InstallationLayout)
    }
}

/** One `/`-separated installation-relative path, built from its segments. */
class InstallationRelativePath private constructor(private val segments: List<String>) {
    /** The manifest form, for example `share/kast/libexec/kast-service`. */
    val value: String
        get() = segments.joinToString(SEPARATOR)

    /** The same path beneath [root]. */
    fun under(root: Path): Path {
        var path = root
        for (segment in segments) path = path.resolve(segment)
        return path
    }

    /** The relative [Path] form, for suffix checks such as [Path.endsWith]. */
    fun asPath(): Path = Path.of(segments.first(), *segments.drop(1).toTypedArray())

    override fun equals(other: Any?): Boolean = other is InstallationRelativePath && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = value

    internal companion object {
        private const val SEPARATOR = "/"

        fun of(vararg segments: String): InstallationRelativePath = InstallationRelativePath(segments.toList())
    }
}

/** The 16-hex per-host coordinator directory name: a prefix of SHA-256 over the selected Codex home path. */
@JvmInline
value class CoordinatorHostProfile private constructor(val value: String) {
    companion object {
        private const val PROFILE_CHARACTERS = 16

        fun of(codexHome: String): CoordinatorHostProfile =
            CoordinatorHostProfile(
                HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(codexHome.toByteArray(StandardCharsets.UTF_8)))
                    .take(PROFILE_CHARACTERS)
            )
    }
}

/** The 64-hex workspace overlay directory name: SHA-256 over the workspace root path. */
@JvmInline
value class WorkspaceOverlayIdentity private constructor(val value: String) {
    companion object {
        fun of(workspaceRoot: Path): WorkspaceOverlayIdentity =
            WorkspaceOverlayIdentity(
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(workspaceRoot.toString().toByteArray(StandardCharsets.UTF_8))
                    )
            )
    }
}
