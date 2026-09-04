package io.github.amichne.kast.distribution.contract.gradle

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Finite rejection at the explicit repository import boundary; values never enter failures. */
enum class GradleImportEnvironmentFailure {
    INVALID_VARIABLE_NAME,
    RESERVED_VARIABLE,
    MISSING_VARIABLE,
    INVALID_VALUE,
    INVALID_EXECUTABLE_PATH,
    TOO_MANY_INPUTS,
}

/** A digest contains no raw environment value and is safe for identity receipts. */
@Serializable
@JvmInline
value class GradleImportEnvironmentIdentity private constructor(val value: String) {
    init { require(Regex("[0-9a-f]{64}").matches(value)) }
    companion object {
        fun parse(raw: String): Refinement<GradleImportEnvironmentIdentity, GradleImportEnvironmentFailure> =
            if (Regex("[0-9a-f]{64}").matches(raw)) {
                Refinement.Refined(GradleImportEnvironmentIdentity(raw))
            } else {
                Refinement.Rejected(GradleImportEnvironmentFailure.INVALID_VALUE)
            }

        fun digest(material: String): GradleImportEnvironmentIdentity =
            GradleImportEnvironmentIdentity(sha256(material))
    }
}

/** An explicitly admitted input name, separate from its confidential process-bound value. */
@JvmInline
value class GradleImportVariableName private constructor(val value: String) {
    companion object {
        /** Refines one POSIX variable name, excluding Kast and process-launch authority. */
        fun parse(raw: String): Refinement<GradleImportVariableName, GradleImportEnvironmentFailure> = when {
            !Regex("[A-Za-z_][A-Za-z0-9_]{0,127}").matches(raw) ->
                Refinement.Rejected(GradleImportEnvironmentFailure.INVALID_VARIABLE_NAME)
            raw.startsWith("KAST_") || raw.startsWith("DYLD_") || raw.startsWith("LD_") ||
                raw in RESERVED -> Refinement.Rejected(GradleImportEnvironmentFailure.RESERVED_VARIABLE)
            else -> Refinement.Refined(GradleImportVariableName(raw))
        }

        private val RESERVED = setOf(
            "JAVA_HOME", "HOME", "PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
            "CLASSPATH", "BASH_ENV", "ENV", "SHELLOPTS", "BASHOPTS", "ZDOTDIR",
        )
    }
}

/** A lexical absolute executable directory. Physical admission belongs to the launch adapter. */
@JvmInline
value class GradleImportExecutableDirectory private constructor(val path: Path) {
    companion object {
        fun parse(raw: String): Refinement<GradleImportExecutableDirectory, GradleImportEnvironmentFailure> {
            val path = try { Path.of(raw) } catch (_: InvalidPathException) {
                return Refinement.Rejected(GradleImportEnvironmentFailure.INVALID_EXECUTABLE_PATH)
            }
            return if (raw.isNotBlank() && raw.length <= 4096 && ':' !in raw &&
                '\n' !in raw && '\r' !in raw && path.isAbsolute && path.normalize() == path
            ) {
                Refinement.Refined(GradleImportExecutableDirectory(path))
            } else {
                Refinement.Rejected(GradleImportEnvironmentFailure.INVALID_EXECUTABLE_PATH)
            }
        }
    }
}

/** Safe public evidence: admitted name and digest, with no recoverable value representation. */
data class GradleImportVariableEvidence(
    val name: GradleImportVariableName,
    val digest: GradleImportEnvironmentIdentity,
)

/**
 * Explicit import capability. Raw selected values leave only through [processVariables] at the
 * process launch boundary. toString and identity evidence never contain values. Ambient PATH is
 * absent from the model; executable additions preserve their explicit search order.
 */
class GradleImportEnvironment private constructor(
    private val variables: Map<GradleImportVariableName, String>,
    val executableDirectories: List<GradleImportExecutableDirectory>,
) {
    val evidence: List<GradleImportVariableEvidence> = variables.entries.map { (name, value) ->
        GradleImportVariableEvidence(name, GradleImportEnvironmentIdentity.digest(value))
    }
    val identity: GradleImportEnvironmentIdentity = GradleImportEnvironmentIdentity.digest(buildString {
        append("kast-gradle-import-environment-v1\n")
        evidence.forEach { append("env:").append(it.name.value).append(':').append(it.digest.value).append('\n') }
        executableDirectories.forEach { append("path:").append(it.path).append('\n') }
    })

    fun processVariables(): Map<String, String> = variables.mapKeys { it.key.value }
    override fun toString(): String = "GradleImportEnvironment(identity=${identity.value}, names=${evidence.map { it.name.value }})"

    companion object {
        const val VARIABLES_SETTING = "KAST_GRADLE_IMPORT_VARIABLES"
        const val PATH_SETTING = "KAST_GRADLE_IMPORT_PATH"
        val Empty = GradleImportEnvironment(emptyMap(), emptyList())

        /**
         * Refines boundary settings plus the caller environment to an immutable allowlist proof.
         * Missing selected inputs fail closed. No unselected ambient value enters the proof.
         * Raw maps are permitted only at this admission and the process environment boundary.
         */
        fun admit(
            names: String,
            executablePath: String,
            ambient: Map<String, String>,
        ): Refinement<GradleImportEnvironment, GradleImportEnvironmentFailure> {
            val rawNames = if (names.isEmpty()) emptyList() else names.split(',')
            val rawPaths = if (executablePath.isEmpty()) emptyList() else executablePath.split(':')
            if (rawNames.size > 64 || rawPaths.size > 32) {
                return Refinement.Rejected(GradleImportEnvironmentFailure.TOO_MANY_INPUTS)
            }
            val admitted = linkedMapOf<GradleImportVariableName, String>()
            for (raw in rawNames.distinct().sorted()) {
                val name = when (val parsed = GradleImportVariableName.parse(raw)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return parsed
                }
                val value = ambient[raw] ?: return Refinement.Rejected(GradleImportEnvironmentFailure.MISSING_VARIABLE)
                if (value.length > 65536 || '\u0000' in value) {
                    return Refinement.Rejected(GradleImportEnvironmentFailure.INVALID_VALUE)
                }
                admitted[name] = value
            }
            val paths = mutableListOf<GradleImportExecutableDirectory>()
            for (raw in rawPaths.distinct()) {
                when (val parsed = GradleImportExecutableDirectory.parse(raw)) {
                    is Refinement.Refined -> paths += parsed.value
                    is Refinement.Rejected -> return parsed
                }
            }
            return Refinement.Refined(GradleImportEnvironment(admitted.toMap(), paths.toList()))
        }
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
