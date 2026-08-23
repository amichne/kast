package io.github.amichne.kast.workspace.intellij

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

enum class InstalledGradleJvmFailure {
    INVALID_PATH,
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    UNAVAILABLE,
    NOT_DIRECTORY,
    JAVA_EXECUTABLE_UNAVAILABLE,
    RUNTIME_HOME_MISMATCH,
    UNSUPPORTED_JAVA_VERSION,
    ENVIRONMENT_HOME_UNAVAILABLE,
    ENVIRONMENT_HOME_MISMATCH,
}

sealed interface InstalledGradleJvmAdmission {
    data class Admitted(
        val jvm: InstalledGradleJvm,
    ) : InstalledGradleJvmAdmission

    data class Rejected(
        val failure: InstalledGradleJvmFailure,
    ) : InstalledGradleJvmAdmission
}

/** Physical Java home authority for one isolated Gradle import. */
class InstalledGradleJvm private constructor(
    internal val home: Path,
    private val selector: String,
) {
    /** Raw selector extraction is confined to the Gradle project-settings boundary. */
    internal fun projectSettingsSelector(): String = selector

    companion object {
        /**
         * Proof transition: `(String, String?) -> InstalledGradleJvmAdmission`.
         *
         * Establishes an absolute, normalized, physically canonical, non-symlinked Java home
         * containing one regular executable `bin/java`, equal to the current Java 21 process home,
         * plus a process `JAVA_HOME` resolving to that exact home. The returned capability retains
         * the canonical home for isolated project SDK resolution and the `#JAVA_HOME` selector for
         * IDEA's Gradle JVM resolver.
         * [InstalledGradleJvmFailure] is the closed expected failure. Raw path text may leave only
         * for filesystem admission; the selector leaves only at Gradle project settings.
         */
        fun admit(
            raw: String,
            environmentHome: String?,
        ): InstalledGradleJvmAdmission {
            val candidate = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.INVALID_PATH,
                )
            }
            if (!candidate.isAbsolute) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.NOT_ABSOLUTE,
                )
            }
            if (candidate.normalize() != candidate) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.NOT_NORMALIZED,
                )
            }
            if (Files.isSymbolicLink(candidate)) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.UNAVAILABLE,
                )
            }
            val canonical = try {
                candidate.toRealPath()
            } catch (_: IOException) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.UNAVAILABLE,
                )
            } catch (_: SecurityException) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.UNAVAILABLE,
                )
            }
            if (canonical != candidate) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.UNAVAILABLE,
                )
            }
            if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.NOT_DIRECTORY,
                )
            }
            val javaExecutable = canonical.resolve("bin/java")
            if (
                Files.isSymbolicLink(javaExecutable) ||
                !Files.isRegularFile(javaExecutable, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isExecutable(javaExecutable)
            ) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.JAVA_EXECUTABLE_UNAVAILABLE,
                )
            }
            val rawRuntimeHome = System.getProperty("java.home")
                                 ?: return InstalledGradleJvmAdmission.Rejected(
                                     InstalledGradleJvmFailure.RUNTIME_HOME_MISMATCH,
                                 )
            val runtimeHome = try {
                Path.of(rawRuntimeHome).toRealPath()
            } catch (_: IOException) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.RUNTIME_HOME_MISMATCH,
                )
            } catch (_: InvalidPathException) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.RUNTIME_HOME_MISMATCH,
                )
            } catch (_: SecurityException) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.RUNTIME_HOME_MISMATCH,
                )
            }
            if (runtimeHome != canonical) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.RUNTIME_HOME_MISMATCH,
                )
            }
            if (Runtime.version().feature() != REQUIRED_JAVA_FEATURE) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.UNSUPPORTED_JAVA_VERSION,
                )
            }
            val environmentCandidate = environmentHome?.let { value ->
                try {
                    Path.of(value)
                } catch (_: InvalidPathException) {
                    null
                }
            } ?: return InstalledGradleJvmAdmission.Rejected(
                InstalledGradleJvmFailure.ENVIRONMENT_HOME_UNAVAILABLE,
            )
            val environmentCanonical = try {
                environmentCandidate.toRealPath()
            } catch (_: IOException) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.ENVIRONMENT_HOME_UNAVAILABLE,
                )
            } catch (_: SecurityException) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.ENVIRONMENT_HOME_UNAVAILABLE,
                )
            }
            if (environmentCanonical != canonical) {
                return InstalledGradleJvmAdmission.Rejected(
                    InstalledGradleJvmFailure.ENVIRONMENT_HOME_MISMATCH,
                )
            }
            return InstalledGradleJvmAdmission.Admitted(
                InstalledGradleJvm(canonical, "#JAVA_HOME"),
            )
        }
    }
}

private const val REQUIRED_JAVA_FEATURE = 21
