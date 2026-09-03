package io.github.amichne.kast.workspace.intellij

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

enum class InstalledSidecarJvmFailure {
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

sealed interface InstalledSidecarJvmAdmission {
    data class Admitted(
        val jvm: InstalledSidecarJvm,
    ) : InstalledSidecarJvmAdmission

    data class Rejected(
        val failure: InstalledSidecarJvmFailure,
    ) : InstalledSidecarJvmAdmission
}

/** Physical Java home authority for the isolated IntelliJ sidecar process only. */
class InstalledSidecarJvm private constructor(
    internal val home: Path,
) {
    companion object {
        /**
         * Proof transition: `(String, String?) -> InstalledSidecarJvmAdmission`.
         *
         * Establishes an absolute, normalized, physically canonical, non-symlinked Java home
         * containing one regular executable `bin/java`, equal to the current Java 25 process home,
         * plus a process `JAVA_HOME` resolving to that exact home. The returned capability retains
         * the canonical home only for sidecar runtime identity and temporary project-open bootstrap.
         * It carries no Gradle JVM selector or imported project-model authority.
         * [InstalledSidecarJvmFailure] is the closed expected failure. Raw path text may leave only
         * for filesystem admission.
         */
        fun admit(
            raw: String,
            environmentHome: String?,
        ): InstalledSidecarJvmAdmission {
            val candidate = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.INVALID_PATH,
                )
            }
            if (!candidate.isAbsolute) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.NOT_ABSOLUTE,
                )
            }
            if (candidate.normalize() != candidate) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.NOT_NORMALIZED,
                )
            }
            if (Files.isSymbolicLink(candidate)) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.UNAVAILABLE,
                )
            }
            val canonical = try {
                candidate.toRealPath()
            } catch (_: IOException) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.UNAVAILABLE,
                )
            } catch (_: SecurityException) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.UNAVAILABLE,
                )
            }
            if (canonical != candidate) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.UNAVAILABLE,
                )
            }
            if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.NOT_DIRECTORY,
                )
            }
            val javaExecutable = canonical.resolve("bin/java")
            if (
                Files.isSymbolicLink(javaExecutable) ||
                !Files.isRegularFile(javaExecutable, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isExecutable(javaExecutable)
            ) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.JAVA_EXECUTABLE_UNAVAILABLE,
                )
            }
            val rawRuntimeHome = System.getProperty("java.home")
                ?: return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.RUNTIME_HOME_MISMATCH,
                )
            val runtimeHome = try {
                Path.of(rawRuntimeHome).toRealPath()
            } catch (_: IOException) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.RUNTIME_HOME_MISMATCH,
                )
            } catch (_: InvalidPathException) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.RUNTIME_HOME_MISMATCH,
                )
            } catch (_: SecurityException) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.RUNTIME_HOME_MISMATCH,
                )
            }
            if (runtimeHome != canonical) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.RUNTIME_HOME_MISMATCH,
                )
            }
            if (Runtime.version().feature() != REQUIRED_JAVA_FEATURE) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.UNSUPPORTED_JAVA_VERSION,
                )
            }
            val environmentCandidate = environmentHome?.let { value ->
                try {
                    Path.of(value)
                } catch (_: InvalidPathException) {
                    null
                }
            } ?: return InstalledSidecarJvmAdmission.Rejected(
                InstalledSidecarJvmFailure.ENVIRONMENT_HOME_UNAVAILABLE,
            )
            val environmentCanonical = try {
                environmentCandidate.toRealPath()
            } catch (_: IOException) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.ENVIRONMENT_HOME_UNAVAILABLE,
                )
            } catch (_: SecurityException) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.ENVIRONMENT_HOME_UNAVAILABLE,
                )
            }
            if (environmentCanonical != canonical) {
                return InstalledSidecarJvmAdmission.Rejected(
                    InstalledSidecarJvmFailure.ENVIRONMENT_HOME_MISMATCH,
                )
            }
            return InstalledSidecarJvmAdmission.Admitted(
                InstalledSidecarJvm(canonical),
            )
        }
    }
}

private const val REQUIRED_JAVA_FEATURE = 25
