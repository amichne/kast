package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationDisclosure
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** The cold launch contract. Warm requests use the owned readiness record directly. */
internal class SemanticServiceLaunch
private constructor(
    val installation: Path,
    val home: Path,
    val executable: Path,
    val readiness: Path,
    val label: String,
    val arguments: List<String>,
) {
    val state: Path
        get() = readiness.parent

    val log: Path
        get() = state.resolve("semantic-service.log")

    val lock: Path
        get() = state.resolve("service-start.lock")

    companion object {
        fun admit(installation: Path, home: Path, environment: Map<String, String>): SemanticServiceLaunch? {
            val executable = installation.resolve("share/kast/libexec/kast-management")
            if (
                !Files.isRegularFile(executable, NOFOLLOW_LINKS) ||
                    !Files.isExecutable(executable) ||
                    executable.toRealPath() != executable
            )
                return null
            if (home.toRealPath() != home) return null
            val readiness = InstalledSemanticCoordinator.readinessPath(installation, home)
            val identity = semanticServiceDigest(installation)
            val explicit =
                environment.filterKeys { it in inheritedKeys } +
                    mapOf(
                        "HOME" to home.toString(),
                        "BROKER_SERVICE_IDENTITY" to semanticServiceIdentity(installation),
                        "BROKER_READINESS_FILE" to readiness.toString(),
                    )
            val arguments =
                listOf("/usr/bin/env", "-i") +
                    explicit.entries.sortedBy { it.key }.map { (key, value) -> "$key=$value" } +
                    listOf(executable.toString(), "-Duser.home=$home", "--internal-entrypoint=daemon", "--semantic")
            return SemanticServiceLaunch(
                installation,
                home,
                executable,
                readiness,
                "io.github.amichne.kast.semantic.${identity.take(SERVICE_LABEL_DIGEST_WIDTH)}",
                arguments,
            )
        }

        /**
         * The persistent coordinator as JVM bytecode. [root] holds this coordinator's private state and publication;
         * [java] must be an admitted runtime, and its process command is the peer executable that ownership proves.
         */
        fun jvm(
            root: Path,
            home: Path,
            java: BrokerJavaRuntime,
            command: JvmCoordinatorCommand,
            environment: Map<String, String>,
        ): SemanticServiceLaunch? {
            if (root.toRealPath() != root || home.toRealPath() != home) return null
            val readiness = InstalledSemanticCoordinator.readinessPath(root, home)
            val explicit =
                environment.filterKeys { it in inheritedKeys } +
                    mapOf(
                        "HOME" to home.toString(),
                        "BROKER_SERVICE_IDENTITY" to semanticServiceIdentity(root),
                        "BROKER_READINESS_FILE" to readiness.toString(),
                    )
            val arguments =
                listOf("/usr/bin/env", "-i") +
                    explicit.entries.sortedBy { it.key }.map { (key, value) -> "$key=$value" } +
                    listOf(
                        java.executable.toString(),
                        "-Duser.home=$home",
                        "-cp",
                        command.classPath,
                        command.mainClass,
                    ) +
                    command.arguments
            return SemanticServiceLaunch(
                root,
                home,
                java.executable,
                readiness,
                "io.github.amichne.kast.semantic.${semanticServiceDigest(root).take(SERVICE_LABEL_DIGEST_WIDTH)}",
                arguments,
            )
        }

        private val inheritedKeys =
            ConfigurationParameter.entries
                .filter {
                    it.declaration().disclosure != ConfigurationDisclosure.SECRET_PRESENCE &&
                        it !in
                            setOf(
                                ConfigurationParameter.CONFIGURATION_FILE,
                                ConfigurationParameter.GRADLE_IMPORT_VARIABLES,
                            )
                }
                .map { it.key }
                .toSet() +
                setOf(
                    "PATH",
                    "JAVA_HOME",
                    "TMPDIR",
                    "XDG_CONFIG_HOME",
                    "XDG_CACHE_HOME",
                    "XDG_DATA_HOME",
                    "XDG_STATE_HOME",
                )
    }
}

object InstalledSemanticService {
    fun submit(
        installation: Path,
        home: Path,
        environment: Map<String, String>,
    ): Refinement<Unit, PersistentBrokerServiceFailure> =
        try {
            val command = SemanticServiceLaunch.admit(installation, home, environment)
            if (command == null) Refinement.Rejected(PersistentBrokerServiceFailure.KAST_EXECUTABLE_UNAVAILABLE)
            else submit(command)
        } catch (_: IOException) {
            Refinement.Rejected(PersistentBrokerServiceFailure.STATE_DIRECTORY_REJECTED)
        } catch (_: SecurityException) {
            Refinement.Rejected(PersistentBrokerServiceFailure.STATE_DIRECTORY_REJECTED)
        }

    internal fun submit(command: SemanticServiceLaunch): Refinement<Unit, PersistentBrokerServiceFailure> =
        try {
            serviceHost()?.submitSemantic(command) ?: Refinement.Rejected(PersistentBrokerServiceFailure.UNAVAILABLE)
        } catch (_: IOException) {
            Refinement.Rejected(PersistentBrokerServiceFailure.STATE_DIRECTORY_REJECTED)
        } catch (_: SecurityException) {
            Refinement.Rejected(PersistentBrokerServiceFailure.STATE_DIRECTORY_REJECTED)
        }

    private fun serviceHost(): PersistentBrokerServiceHost? =
        when (System.getProperty("os.name")) {
            "Mac OS X" -> MacOsPersistentBrokerServiceHost()
            "Linux" -> LinuxPersistentBrokerServiceHost()
            else -> null
        }
}

/** The JVM entry and its arguments; the launch contract supplies the runtime, environment and readiness. */
class JvmCoordinatorCommand(val classPath: String, val mainClass: String, val arguments: List<String>)

/** Starts or joins the JVM coordinator published under one coordinator root. */
object InstalledJvmCoordinator {
    suspend fun connect(
        root: Path,
        home: Path,
        javaHome: Path,
        command: JvmCoordinatorCommand,
        environment: Map<String, String>,
    ): Refinement<InstalledSemanticCoordinator, SemanticCoordinatorStartupFailure> =
        when (val selected = launch(root, home, javaHome, command, environment)) {
            is Refinement.Rejected ->
                Refinement.Rejected(SemanticCoordinatorStartupFailure.SubmissionRejected(selected.failure))
            is Refinement.Refined ->
                InstalledSemanticCoordinator.connect(root, selected.value.readiness, selected.value.executable) {
                    InstalledSemanticService.submit(selected.value)
                }
        }

    /** Withdraws this root's supervised job; the coordinator drains its owned Hosts on shutdown. */
    fun withdraw(
        root: Path,
        home: Path,
        javaHome: Path,
        command: JvmCoordinatorCommand,
    ): Refinement<Unit, PersistentBrokerServiceFailure> =
        when (val selected = launch(root, home, javaHome, command, emptyMap())) {
            is Refinement.Rejected -> selected
            is Refinement.Refined ->
                if (System.getProperty("os.name") != "Mac OS X")
                    Refinement.Rejected(PersistentBrokerServiceFailure.UNAVAILABLE)
                else if (withdrawSemanticLaunchdJob(selected.value)) Refinement.Refined(Unit)
                else Refinement.Rejected(PersistentBrokerServiceFailure.SERVICE_SUBMISSION_REJECTED)
        }

    private fun launch(
        root: Path,
        home: Path,
        javaHome: Path,
        command: JvmCoordinatorCommand,
        environment: Map<String, String>,
    ): Refinement<SemanticServiceLaunch, PersistentBrokerServiceFailure> {
        val runtime =
            when (val admitted = admitJavaRuntime(javaHome)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> admitted.value
            }
        val launch =
            try {
                SemanticServiceLaunch.jvm(root, home, runtime, command, environment)
            } catch (_: IOException) {
                null
            }
        return launch?.let { Refinement.Refined(it) }
            ?: Refinement.Rejected(PersistentBrokerServiceFailure.KAST_EXECUTABLE_UNAVAILABLE)
    }

    private fun admitJavaRuntime(home: Path): Refinement<BrokerJavaRuntime, PersistentBrokerServiceFailure> {
        val executable =
            try {
                home.resolve("bin/java").toRealPath()
            } catch (_: IOException) {
                return Refinement.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE)
            }
        if (!Files.isExecutable(executable))
            return Refinement.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE)
        return BrokerJavaRuntime.admit(home, executable)
    }
}

/** The readiness identity the launch contract assigns to one physical installation's semantic service. */
internal fun semanticServiceIdentity(installation: Path): String = "sha256:${semanticServiceDigest(installation)}"

private fun semanticServiceDigest(installation: Path): String =
    HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(installation.toString().toByteArray(Charsets.UTF_8)))

private const val SERVICE_LABEL_DIGEST_WIDTH = 32
