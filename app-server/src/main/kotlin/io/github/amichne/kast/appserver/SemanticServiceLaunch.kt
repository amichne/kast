package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.InstallationLayout
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
            val executable = InstallationLayout.of(installation).payload.managementExecutable
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
            else
                serviceHost()?.submitSemantic(command)
                    ?: Refinement.Rejected(PersistentBrokerServiceFailure.UNAVAILABLE)
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

/** The readiness identity the launch contract assigns to one physical installation's semantic service. */
internal fun semanticServiceIdentity(installation: Path): String = "sha256:${semanticServiceDigest(installation)}"

private fun semanticServiceDigest(installation: Path): String =
    HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(installation.toString().toByteArray(Charsets.UTF_8)))

private const val SERVICE_LABEL_DIGEST_WIDTH = 32
