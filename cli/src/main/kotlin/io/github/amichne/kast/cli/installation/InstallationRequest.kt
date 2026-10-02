package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.BrokerJvmUserHomeOption
import io.github.amichne.kast.appserver.BrokerPublicEndpointMode
import io.github.amichne.kast.distribution.contract.configuration.RetiredConfigurationSetting
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal enum class InstallationEnvironment(val key: String) {
    CONTROL_ROOT("KAST_INSTALL_CONTROL_ROOT"),
    CONTROL_ARCHIVE("KAST_INSTALL_CONTROL_ARCHIVE"),
    CONTROL_SHA256("KAST_INSTALL_CONTROL_SHA256"),
    VERSION("KAST_INSTALL_VERSION"),
    IDEA_HOME("KAST_INSTALL_IDEA_HOME"),
    JAVA_HOME("KAST_INSTALL_JAVA_HOME"),
    INSTALL_ROOT("KAST_INSTALL_ROOT"),
    BIN_DIRECTORY("KAST_BIN_DIR"),
    HOME("HOME"),
    CODEX_HOME("CODEX_HOME"),
    PUBLIC_ENDPOINT("KAST_APP_SERVER_PUBLIC_ENDPOINT"),
    PROFILE("KAST_INSTALL_PROFILE"),
    MODE("KAST_INSTALL_MODE"),
    FORCE("KAST_INSTALL_FORCE"),
    CONTROL_ONLY("KAST_INSTALL_CONTROL_ONLY"),
}

internal enum class InstallationMode {
    PLAN,
    APPLY,
}

internal data class SemanticVersion
internal constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(raw: String): Refinement<SemanticVersion, Unit> {
            val parts = raw.split('.')
            if (parts.size != 3 || parts.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) {
                return Refinement.Rejected(Unit)
            }
            val values = parts.map { it.toIntOrNull() ?: return Refinement.Rejected(Unit) }
            return Refinement.Refined(SemanticVersion(values[0], values[1], values[2]))
        }
    }
}

@JvmInline
internal value class InstallationPath private constructor(val value: Path) {
    companion object {
        fun parse(raw: String): Refinement<InstallationPath, Unit> {
            if (raw.any { it == '\n' || it == '\r' || it == '\u0000' }) return Refinement.Rejected(Unit)
            val path =
                try {
                    Path.of(raw)
                } catch (_: InvalidPathException) {
                    return Refinement.Rejected(Unit)
                }
            return if (path.isAbsolute && path.normalize() == path) {
                Refinement.Refined(InstallationPath(path))
            } else {
                Refinement.Rejected(Unit)
            }
        }
    }
}

@JvmInline
internal value class Sha256 private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<Sha256, Unit> =
            if (raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' }) {
                Refinement.Refined(Sha256(raw))
            } else {
                Refinement.Rejected(Unit)
            }
    }
}

internal enum class InstallationSwitch {
    DISABLED,
    ENABLED,
}

/** The sole per-user installation owns a login service. */
internal enum class InstallationProfile {
    PERSISTENT
}

@JvmInline
internal value class UserInstallationRoot private constructor(val value: Path) {
    companion object {
        fun admit(
            home: InstallationPath,
            root: InstallationPath,
        ): Refinement<UserInstallationRoot, InstallationRequestFailure> =
            if (root.value == home.value.resolve(".local/share/kast"))
                Refinement.Refined(UserInstallationRoot(root.value))
            else Refinement.Rejected(InstallationRequestFailure.InvalidPath(InstallationEnvironment.INSTALL_ROOT))
    }
}

@JvmInline
internal value class UserInstallationBin private constructor(val value: Path) {
    companion object {
        fun admit(
            home: InstallationPath,
            directory: InstallationPath,
        ): Refinement<UserInstallationBin, InstallationRequestFailure> =
            if (directory.value == home.value.resolve(".local/bin"))
                Refinement.Refined(UserInstallationBin(directory.value))
            else Refinement.Rejected(InstallationRequestFailure.InvalidPath(InstallationEnvironment.BIN_DIRECTORY))
    }
}

internal sealed interface InstallationRequestFailure {
    data class RetiredSetting(val setting: RetiredConfigurationSetting) : InstallationRequestFailure

    data class Missing(val environment: InstallationEnvironment) : InstallationRequestFailure

    data class InvalidPath(val environment: InstallationEnvironment) : InstallationRequestFailure

    data class InvalidValue(val environment: InstallationEnvironment) : InstallationRequestFailure
}

/** One bootstrap environment whose raw paths and scalar controls have been refined exactly once. */
internal data class ControlPayload(
    val root: InstallationPath,
    val archive: InstallationPath,
    val digest: Sha256,
)

internal typealias InstallationRequest = ControlInstallRequest

internal data class ControlInstallRequest
private constructor(
    val payload: ControlPayload,
    val version: SemanticVersion,
    val ideaHome: InstallationPath,
    val javaHome: InstallationPath,
    val installRoot: UserInstallationRoot,
    val binDirectory: UserInstallationBin,
    val home: InstallationPath,
    val jvmUserHomeOption: BrokerJvmUserHomeOption,
    val codexHome: InstallationPath,
    val profile: InstallationProfile,
    val mode: InstallationMode,
    val publicEndpoint: BrokerPublicEndpointMode,
    val force: InstallationSwitch,
    val controlOnly: InstallationSwitch,
) {
    val controlRoot: InstallationPath
        get() = payload.root

    val controlArchive: InstallationPath
        get() = payload.archive

    val controlDigest: Sha256
        get() = payload.digest

    companion object {
        fun parse(environment: Map<String, String>): Refinement<InstallationRequest, InstallationRequestFailure> {
            RetiredConfigurationSetting.entries
                .firstOrNull { it.key in environment }
                ?.let {
                    return Refinement.Rejected(InstallationRequestFailure.RetiredSetting(it))
                }
            val input = ControlInstallationInput(environment)
            val payload =
                when (val admitted = input.payload()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val version =
                when (val admitted = input.version()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val locations =
                when (val admitted = input.locations()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val options =
                when (val admitted = input.options()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            return Refinement.Refined(
                ControlInstallRequest(
                    payload = payload,
                    version = version,
                    ideaHome = locations.ideaHome,
                    javaHome = locations.javaHome,
                    installRoot = locations.installRoot,
                    binDirectory = locations.binDirectory,
                    home = locations.home,
                    jvmUserHomeOption = locations.jvmUserHomeOption,
                    codexHome = locations.codexHome,
                    profile = options.profile,
                    mode = options.mode,
                    publicEndpoint = options.endpoint,
                    force = options.force,
                    controlOnly = options.controlOnly,
                )
            )
        }
    }
}
