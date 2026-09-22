package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.BrokerPublicEndpointMode
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.AgentToolDefinition
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal enum class InstallationEnvironment(val key: String) {
    CONTROL_ROOT("KAST_INSTALL_CONTROL_ROOT"),
    CONTROL_ARCHIVE("KAST_INSTALL_CONTROL_ARCHIVE"),
    CONTROL_SHA256("KAST_INSTALL_CONTROL_SHA256"),
    HOSTED_PLUGIN_ARCHIVE("KAST_INSTALL_HOSTED_PLUGIN_ARCHIVE"),
    HOSTED_PLUGIN_SHA256("KAST_INSTALL_HOSTED_PLUGIN_SHA256"),
    VERSION("KAST_INSTALL_VERSION"),
    IDEA_HOME("KAST_INSTALL_IDEA_HOME"),
    JAVA_HOME("KAST_INSTALL_JAVA_HOME"),
    INSTALL_ROOT("KAST_INSTALL_ROOT"),
    BIN_DIRECTORY("KAST_BIN_DIR"),
    HOME("HOME"),
    CODEX_HOME("CODEX_HOME"),
    ENABLE_LAUNCHD("KAST_ENABLE_LAUNCHD"),
    APP_SERVER_TOOLS("KAST_APP_SERVER_TOOLS"),
    PUBLIC_ENDPOINT("KAST_APP_SERVER_PUBLIC_ENDPOINT"),
    REFRESH_APP_SERVER("KAST_INSTALL_REFRESH_APP_SERVER"),
    REPLACE_COMMAND_COLLISIONS("KAST_INSTALL_REPLACE_COMMAND_COLLISIONS"),
    MODE("KAST_INSTALL_MODE"),
    FORCE("KAST_INSTALL_FORCE"),
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

internal enum class AppServerToolsFailure {
    EMPTY_NAME,
    DUPLICATE_NAME,
    UNKNOWN_NAME,
}

/** A canonical, non-empty tool selection; installation cannot persist retired tool identities. */
internal class AppServerTools private constructor(private val definitions: List<AgentToolDefinition>) {
    val value: String
        get() = definitions.joinToString(",") { it.name.value }

    companion object {
        fun parse(raw: String?): Refinement<AppServerTools, AppServerToolsFailure> {
            if (raw == null)
                return Refinement.Refined(AppServerTools(CanonicalAgentToolDefinitions.defaultAppServerTools))
            val tokens = raw.split(',')
            if (tokens.any(String::isBlank)) return Refinement.Rejected(AppServerToolsFailure.EMPTY_NAME)
            val names = tokens.toSet()
            if (names.size != tokens.size) return Refinement.Rejected(AppServerToolsFailure.DUPLICATE_NAME)
            val admitted = tokens.map { token ->
                when (val resolved = CanonicalAgentToolDefinitions.resolveInput(token)) {
                    is Refinement.Refined -> resolved.value.name
                    is Refinement.Rejected -> return Refinement.Rejected(AppServerToolsFailure.UNKNOWN_NAME)
                }
            }
            if (admitted.toSet().size != admitted.size) {
                return Refinement.Rejected(AppServerToolsFailure.DUPLICATE_NAME)
            }
            val definitions = CanonicalAgentToolDefinitions.all.filter { it.name in admitted }
            return Refinement.Refined(AppServerTools(definitions))
        }
    }
}

internal sealed interface InstallationRequestFailure {
    data class Missing(val environment: InstallationEnvironment) : InstallationRequestFailure

    data class InvalidPath(val environment: InstallationEnvironment) : InstallationRequestFailure

    data class InvalidValue(val environment: InstallationEnvironment) : InstallationRequestFailure
}

/** One bootstrap environment whose raw paths and scalar controls have been refined exactly once. */
internal data class InstallationRequest
private constructor(
    val controlRoot: InstallationPath,
    val controlArchive: InstallationPath,
    val controlDigest: Sha256,
    val pluginArchive: InstallationPath,
    val pluginDigest: Sha256,
    val version: SemanticVersion,
    val ideaHome: InstallationPath,
    val javaHome: InstallationPath,
    val installRoot: InstallationPath,
    val binDirectory: InstallationPath,
    val home: InstallationPath,
    val codexHome: InstallationPath,
    val enableLaunchd: InstallationSwitch,
    val appServerTools: AppServerTools,
    val refreshAppServer: InstallationSwitch,
    val replaceCommandCollisions: InstallationSwitch,
    val mode: InstallationMode,
    val publicEndpoint: BrokerPublicEndpointMode,
    val force: InstallationSwitch,
) {
    companion object {
        fun parse(environment: Map<String, String>): Refinement<InstallationRequest, InstallationRequestFailure> {
            fun raw(name: InstallationEnvironment): Refinement<String, InstallationRequestFailure> =
                (environment[name.key] ?: if (name == InstallationEnvironment.FORCE) "0" else null)?.let(
                    Refinement<String, InstallationRequestFailure>::Refined
                ) ?: Refinement.Rejected(InstallationRequestFailure.Missing(name))

            fun path(name: InstallationEnvironment): Refinement<InstallationPath, InstallationRequestFailure> =
                when (val value = raw(name)) {
                    is Refinement.Rejected -> value
                    is Refinement.Refined ->
                        when (val parsed = InstallationPath.parse(value.value)) {
                            is Refinement.Refined -> parsed
                            is Refinement.Rejected -> Refinement.Rejected(InstallationRequestFailure.InvalidPath(name))
                        }
                }

            fun digest(name: InstallationEnvironment): Refinement<Sha256, InstallationRequestFailure> =
                when (val value = raw(name)) {
                    is Refinement.Rejected -> value
                    is Refinement.Refined ->
                        when (val parsed = Sha256.parse(value.value)) {
                            is Refinement.Refined -> parsed
                            is Refinement.Rejected -> Refinement.Rejected(InstallationRequestFailure.InvalidValue(name))
                        }
                }

            fun switch(name: InstallationEnvironment): Refinement<InstallationSwitch, InstallationRequestFailure> =
                when (val value = raw(name)) {
                    is Refinement.Rejected -> value
                    is Refinement.Refined ->
                        when (value.value) {
                            "0" -> Refinement.Refined(InstallationSwitch.DISABLED)
                            "1" -> Refinement.Refined(InstallationSwitch.ENABLED)
                            else -> Refinement.Rejected(InstallationRequestFailure.InvalidValue(name))
                        }
                }

            val controlRoot =
                when (val refined = path(InstallationEnvironment.CONTROL_ROOT)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val controlArchive =
                when (val refined = path(InstallationEnvironment.CONTROL_ARCHIVE)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val controlDigest =
                when (val refined = digest(InstallationEnvironment.CONTROL_SHA256)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val pluginArchive =
                when (val refined = path(InstallationEnvironment.HOSTED_PLUGIN_ARCHIVE)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val pluginDigest =
                when (val refined = digest(InstallationEnvironment.HOSTED_PLUGIN_SHA256)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val versionRaw =
                when (val refined = raw(InstallationEnvironment.VERSION)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val version =
                when (val parsed = SemanticVersion.parse(versionRaw)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(
                            InstallationRequestFailure.InvalidValue(InstallationEnvironment.VERSION)
                        )
                }
            val ideaHome =
                when (val refined = path(InstallationEnvironment.IDEA_HOME)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val javaHome =
                when (val refined = path(InstallationEnvironment.JAVA_HOME)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val installRoot =
                when (val refined = path(InstallationEnvironment.INSTALL_ROOT)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val binDirectory =
                when (val refined = path(InstallationEnvironment.BIN_DIRECTORY)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val home =
                when (val refined = path(InstallationEnvironment.HOME)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val codexHome =
                when (val refined = path(InstallationEnvironment.CODEX_HOME)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val enableLaunchd =
                when (val refined = switch(InstallationEnvironment.ENABLE_LAUNCHD)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val tools =
                when (val parsed = AppServerTools.parse(environment[InstallationEnvironment.APP_SERVER_TOOLS.key])) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(
                            InstallationRequestFailure.InvalidValue(InstallationEnvironment.APP_SERVER_TOOLS)
                        )
                }
            val endpoint =
                when (
                    val admitted =
                        BrokerPublicEndpointMode.admit(environment[InstallationEnvironment.PUBLIC_ENDPOINT.key])
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(
                            InstallationRequestFailure.InvalidValue(InstallationEnvironment.PUBLIC_ENDPOINT)
                        )
                }
            val refresh =
                when (val refined = switch(InstallationEnvironment.REFRESH_APP_SERVER)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val replaceCommandCollisions =
                when (val refined = switch(InstallationEnvironment.REPLACE_COMMAND_COLLISIONS)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val force =
                when (val refined = switch(InstallationEnvironment.FORCE)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val modeRaw =
                when (val refined = raw(InstallationEnvironment.MODE)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val mode =
                when (modeRaw) {
                    "plan" -> InstallationMode.PLAN
                    "apply" -> InstallationMode.APPLY
                    else ->
                        return Refinement.Rejected(
                            InstallationRequestFailure.InvalidValue(InstallationEnvironment.MODE)
                        )
                }
            return Refinement.Refined(
                InstallationRequest(
                    controlRoot,
                    controlArchive,
                    controlDigest,
                    pluginArchive,
                    pluginDigest,
                    version,
                    ideaHome,
                    javaHome,
                    installRoot,
                    binDirectory,
                    home,
                    codexHome,
                    enableLaunchd,
                    tools,
                    refresh,
                    replaceCommandCollisions,
                    mode,
                    endpoint,
                    force,
                )
            )
        }
    }
}
