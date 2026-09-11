package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal enum class InstallationEnvironment(val key: String) {
    CONTROL_ROOT("KAST_INSTALL_CONTROL_ROOT"),
    CONTROL_ARCHIVE("KAST_INSTALL_CONTROL_ARCHIVE"),
    CONTROL_SHA256("KAST_INSTALL_CONTROL_SHA256"),
    RUNTIME_ARCHIVE("KAST_INSTALL_RUNTIME_ARCHIVE"),
    RUNTIME_SHA256("KAST_INSTALL_RUNTIME_SHA256"),
    VERSION("KAST_INSTALL_VERSION"),
    IDEA_HOME("KAST_INSTALL_IDEA_HOME"),
    JAVA_HOME("KAST_INSTALL_JAVA_HOME"),
    INSTALL_ROOT("KAST_INSTALL_ROOT"),
    BIN_DIRECTORY("KAST_BIN_DIR"),
    HOME("HOME"),
    CODEX_HOME("CODEX_HOME"),
    ENABLE_LAUNCHD("KAST_ENABLE_LAUNCHD"),
    ENABLE_APP_SERVER("KAST_ENABLE_APP_SERVER"),
    APP_SERVER_TOOLS("KAST_APP_SERVER_TOOLS"),
    REFRESH_APP_SERVER("KAST_INSTALL_REFRESH_APP_SERVER"),
    MODE("KAST_INSTALL_MODE"),
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

@JvmInline
internal value class AppServerTools private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<AppServerTools, Unit> =
            if (raw.isNotBlank() && raw.none { it == '\n' || it == '\r' || it == '\u0000' }) {
                Refinement.Refined(AppServerTools(raw))
            } else {
                Refinement.Rejected(Unit)
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
    val runtimeArchive: InstallationPath,
    val runtimeDigest: Sha256,
    val version: SemanticVersion,
    val ideaHome: InstallationPath,
    val javaHome: InstallationPath,
    val installRoot: InstallationPath,
    val binDirectory: InstallationPath,
    val home: InstallationPath,
    val codexHome: InstallationPath,
    val enableLaunchd: InstallationSwitch,
    val enableAppServer: InstallationSwitch,
    val appServerTools: AppServerTools,
    val refreshAppServer: InstallationSwitch,
    val mode: InstallationMode,
) {
    companion object {
        fun parse(environment: Map<String, String>): Refinement<InstallationRequest, InstallationRequestFailure> {
            fun raw(name: InstallationEnvironment): Refinement<String, InstallationRequestFailure> =
                environment[name.key]?.let(Refinement<String, InstallationRequestFailure>::Refined)
                    ?: Refinement.Rejected(InstallationRequestFailure.Missing(name))

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
            val runtimeArchive =
                when (val refined = path(InstallationEnvironment.RUNTIME_ARCHIVE)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val runtimeDigest =
                when (val refined = digest(InstallationEnvironment.RUNTIME_SHA256)) {
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
            val enableAppServer =
                when (val refined = switch(InstallationEnvironment.ENABLE_APP_SERVER)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val toolsRaw =
                when (val refined = raw(InstallationEnvironment.APP_SERVER_TOOLS)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            val tools =
                when (val parsed = AppServerTools.parse(toolsRaw)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(
                            InstallationRequestFailure.InvalidValue(InstallationEnvironment.APP_SERVER_TOOLS)
                        )
                }
            val refresh =
                when (val refined = switch(InstallationEnvironment.REFRESH_APP_SERVER)) {
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
                    runtimeArchive,
                    runtimeDigest,
                    version,
                    ideaHome,
                    javaHome,
                    installRoot,
                    binDirectory,
                    home,
                    codexHome,
                    enableLaunchd,
                    enableAppServer,
                    tools,
                    refresh,
                    mode,
                )
            )
        }
    }
}
