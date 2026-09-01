package io.github.amichne.kast.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal enum class MacOsRuntimeProcessEnvironmentFailure {
    JAVA_HOME_UNAVAILABLE,
    USER_HOME_UNAVAILABLE,
}

internal sealed interface MacOsRuntimeProcessEnvironmentResolution {
    data class Resolved(
        val environment: MacOsRuntimeProcessEnvironment,
    ) : MacOsRuntimeProcessEnvironmentResolution

    data class Rejected(
        val failure: MacOsRuntimeProcessEnvironmentFailure,
    ) : MacOsRuntimeProcessEnvironmentResolution
}

/** Minimal non-secret environment required by the detached installed indexer. */
internal class MacOsRuntimeProcessEnvironment private constructor(
    val variables: Map<String, String>,
) {
    val assignments: List<String> = variables.map { (name, value) -> "$name=$value" }

    companion object {
        /**
         * Proof transition: `JVM system properties ->
         * MacOsRuntimeProcessEnvironmentResolution`.
         *
         * Establishes canonical physical Java and user homes plus a deterministic executable path
         * for one detached process. [MacOsRuntimeProcessEnvironmentFailure] closes unavailable or
         * malformed host properties. Raw values leave only at the direct [ProcessBuilder] or
         * `/usr/bin/env` launchd boundary; arbitrary caller environment and secrets are not
         * propagated.
         */
        fun resolve(): MacOsRuntimeProcessEnvironmentResolution {
            val javaHome = when (val admission = canonicalDirectory(
                System.getProperty("java.home") ?: return MacOsRuntimeProcessEnvironmentResolution
                    .Rejected(MacOsRuntimeProcessEnvironmentFailure.JAVA_HOME_UNAVAILABLE),
            )) {
                is CanonicalEnvironmentDirectory.Admitted -> admission.path
                CanonicalEnvironmentDirectory.Rejected ->
                    return MacOsRuntimeProcessEnvironmentResolution.Rejected(
                        MacOsRuntimeProcessEnvironmentFailure.JAVA_HOME_UNAVAILABLE,
                    )
            }
            if (!Files.isExecutable(javaHome.resolve("bin/java"))) {
                return MacOsRuntimeProcessEnvironmentResolution.Rejected(
                    MacOsRuntimeProcessEnvironmentFailure.JAVA_HOME_UNAVAILABLE,
                )
            }
            val userHome = when (val admission = canonicalDirectory(
                System.getProperty("user.home") ?: return MacOsRuntimeProcessEnvironmentResolution
                    .Rejected(MacOsRuntimeProcessEnvironmentFailure.USER_HOME_UNAVAILABLE),
            )) {
                is CanonicalEnvironmentDirectory.Admitted -> admission.path
                CanonicalEnvironmentDirectory.Rejected ->
                    return MacOsRuntimeProcessEnvironmentResolution.Rejected(
                        MacOsRuntimeProcessEnvironmentFailure.USER_HOME_UNAVAILABLE,
                    )
            }
            return MacOsRuntimeProcessEnvironmentResolution.Resolved(
                MacOsRuntimeProcessEnvironment(
                    linkedMapOf(
                        "JAVA_HOME" to javaHome.toString(),
                        "HOME" to userHome.toString(),
                        "PATH" to "${javaHome.resolve("bin")}:$SYSTEM_EXECUTABLE_PATH",
                    ),
                ),
            )
        }
    }
}

private sealed interface CanonicalEnvironmentDirectory {
    data class Admitted(
        val path: Path,
    ) : CanonicalEnvironmentDirectory

    data object Rejected : CanonicalEnvironmentDirectory
}

/**
 * Proof transition: `String -> CanonicalEnvironmentDirectory`.
 *
 * [CanonicalEnvironmentDirectory.Admitted] establishes one absolute physical directory.
 * [CanonicalEnvironmentDirectory.Rejected] closes malformed, missing, inaccessible, and
 * non-directory paths. Raw text leaves only at the filesystem boundary.
 */
private fun canonicalDirectory(raw: String): CanonicalEnvironmentDirectory = try {
    val candidate = Path.of(raw)
    if (!candidate.isAbsolute) return CanonicalEnvironmentDirectory.Rejected
    val canonical = candidate.toRealPath()
    if (Files.isDirectory(canonical)) {
        CanonicalEnvironmentDirectory.Admitted(canonical)
    } else {
        CanonicalEnvironmentDirectory.Rejected
    }
} catch (_: InvalidPathException) {
    CanonicalEnvironmentDirectory.Rejected
} catch (_: IOException) {
    CanonicalEnvironmentDirectory.Rejected
} catch (_: SecurityException) {
    CanonicalEnvironmentDirectory.Rejected
}

private const val SYSTEM_EXECUTABLE_PATH = "/usr/bin:/bin:/usr/sbin:/sbin"
