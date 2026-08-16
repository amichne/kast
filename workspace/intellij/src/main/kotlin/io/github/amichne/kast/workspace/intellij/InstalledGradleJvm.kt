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
    private val home: Path,
) {
    /** Raw selector extraction is confined to the Gradle project-settings boundary. */
    internal fun projectSettingsSelector(): String = home.toString()

    companion object {
        /**
         * Proof transition: `String -> InstalledGradleJvmAdmission`.
         *
         * Establishes an absolute, normalized, physically canonical, non-symlinked Java home
         * containing one regular executable `bin/java`. [InstalledGradleJvmFailure] is the closed
         * expected failure. Raw path text may leave only for filesystem admission and the Gradle
         * project-settings boundary.
         */
        fun admit(raw: String): InstalledGradleJvmAdmission {
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
            return InstalledGradleJvmAdmission.Admitted(InstalledGradleJvm(canonical))
        }
    }
}
