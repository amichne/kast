package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentFailure
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class MacOsRuntimeProcessEnvironmentFailure {
    JAVA_HOME_UNAVAILABLE,
    USER_HOME_UNAVAILABLE,
    GRADLE_IMPORT_ENVIRONMENT_REJECTED,
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
         * Proof transition: `InstalledIdeRuntime + user.home ->
         * MacOsRuntimeProcessEnvironmentResolution`.
         *
         * Establishes the canonical physical Java home from the already-admitted IDEA JBR, plus
         * a canonical user home and deterministic executable path for one detached process.
         * [MacOsRuntimeProcessEnvironmentFailure] closes unavailable or malformed paths. Raw
         * values leave only at the direct [ProcessBuilder] or `/usr/bin/env` launchd boundary;
         * the initiating CLI JVM, arbitrary caller environment, and secrets are not propagated.
         */
        fun resolve(
            runtime: InstalledIdeRuntime,
        ): MacOsRuntimeProcessEnvironmentResolution = when (val admission = currentGradleImportEnvironment()) {
            is Refinement.Refined -> resolve(runtime, admission.value)
            is Refinement.Rejected -> MacOsRuntimeProcessEnvironmentResolution.Rejected(
                MacOsRuntimeProcessEnvironmentFailure.GRADLE_IMPORT_ENVIRONMENT_REJECTED,
            )
        }

        fun resolve(
            runtime: InstalledIdeRuntime,
            admittedImport: GradleImportEnvironment,
        ): MacOsRuntimeProcessEnvironmentResolution {
            val javaHome = when (
                val admission = canonicalJavaHome(runtime)
            ) {
                is CanonicalEnvironmentDirectory.Admitted -> admission.path
                CanonicalEnvironmentDirectory.Rejected ->
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
                        "PATH" to (listOf(javaHome.resolve("bin").toString()) +
                            admittedImport.executableDirectories.map { it.path.toString() } +
                            SYSTEM_EXECUTABLE_PATH).joinToString(":"),
                    ).apply {
                        putAll(admittedImport.processVariables())
                        if (admittedImport.evidence.isNotEmpty() || admittedImport.executableDirectories.isNotEmpty()) {
                            put(GradleImportEnvironment.VARIABLES_SETTING,
                                admittedImport.evidence.joinToString(",") { it.name.value })
                            put(GradleImportEnvironment.PATH_SETTING,
                                admittedImport.executableDirectories.joinToString(":") { it.path.toString() })
                        }
                    },
                ),
            )
        }
    }
}

/** Refines an installed IDEA runtime to the exact bundled JBR home that owns its `bin/java`. */
private fun canonicalJavaHome(runtime: InstalledIdeRuntime): CanonicalEnvironmentDirectory {
    val ideaHome = when (val admission = canonicalDirectory(runtime.home.toString())) {
        is CanonicalEnvironmentDirectory.Admitted -> admission.path
        CanonicalEnvironmentDirectory.Rejected -> return CanonicalEnvironmentDirectory.Rejected
    }
    val javaExecutable = runtime.javaExecutable
    val expectedJava = try {
        ideaHome.resolve("jbr/Contents/Home/bin/java").toRealPath()
    } catch (_: IOException) {
        return CanonicalEnvironmentDirectory.Rejected
    } catch (_: SecurityException) {
        return CanonicalEnvironmentDirectory.Rejected
    }
    if (javaExecutable != expectedJava) {
        return CanonicalEnvironmentDirectory.Rejected
    }
    if (
        !javaExecutable.isAbsolute || javaExecutable.normalize() != javaExecutable ||
        Files.isSymbolicLink(javaExecutable) ||
        !Files.isRegularFile(javaExecutable, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isExecutable(javaExecutable) || javaExecutable.fileName.toString() != "java" ||
        javaExecutable.parent?.fileName?.toString() != "bin"
    ) {
        return CanonicalEnvironmentDirectory.Rejected
    }
    val canonicalJava = try {
        javaExecutable.toRealPath()
    } catch (_: IOException) {
        return CanonicalEnvironmentDirectory.Rejected
    } catch (_: SecurityException) {
        return CanonicalEnvironmentDirectory.Rejected
    }
    if (canonicalJava != javaExecutable) return CanonicalEnvironmentDirectory.Rejected
    val rawHome = javaExecutable.parent?.parent
        ?: return CanonicalEnvironmentDirectory.Rejected
    return when (val home = canonicalDirectory(rawHome.toString())) {
        is CanonicalEnvironmentDirectory.Admitted -> if (
            home.path.resolve("bin/java") == canonicalJava
        ) {
            home
        } else {
            CanonicalEnvironmentDirectory.Rejected
        }
        CanonicalEnvironmentDirectory.Rejected -> CanonicalEnvironmentDirectory.Rejected
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

/** Environment IO stays at the launcher boundary, before any cache or runtime can be admitted. */
internal fun currentGradleImportEnvironment(
    ambient: Map<String, String> = System.getenv(),
): Refinement<GradleImportEnvironment, GradleImportEnvironmentFailure> {
    val admitted = when (val result = GradleImportEnvironment.admit(
        ambient[GradleImportEnvironment.VARIABLES_SETTING].orEmpty(),
        ambient[GradleImportEnvironment.PATH_SETTING].orEmpty(),
        ambient,
    )) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val physicalPaths = mutableListOf<Path>()
    for (directory in admitted.executableDirectories) {
        when (val physical = canonicalDirectory(directory.path.toString())) {
            is CanonicalEnvironmentDirectory.Admitted -> physicalPaths.add(physical.path)
            CanonicalEnvironmentDirectory.Rejected -> return Refinement.Rejected(GradleImportEnvironmentFailure.INVALID_EXECUTABLE_PATH)
        }
    }
    return GradleImportEnvironment.admit(
        admitted.evidence.joinToString(",") { it.name.value },
        physicalPaths.joinToString(":"),
        admitted.processVariables(),
    )
}
