package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Physical evidence for conventional workspace Gradle inputs at the import boundary.
 *
 * This guard covers build/settings scripts, Gradle properties, version catalogs, and buildSrc or
 * build-logic source. It does not infer arbitrary inputs used by executable build logic. Changed
 * evidence invalidates the imported model; a new import must establish a new guard.
 */
internal class InstalledGradleModelInputs private constructor(
    private val workspaceRoot: Path,
    private val entries: Map<Path, ContentIdentity>,
) {
    /** Returns the original imported-input authority only while its current physical inputs agree. */
    fun current(): Refinement<InstalledGradleModelInputs, InstalledGradleModelCaptureFailure> =
        when (val observed = capture(workspaceRoot)) {
            is Refinement.Rejected -> observed
            is Refinement.Refined -> if (entries == observed.value.entries) {
                Refinement.Refined(this)
            } else {
                Refinement.Rejected(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED)
            }
        }

    /**
     * Refines an observation under this imported-input authority into retained evidence only after
     * matching physical input observations before and after the callback. A moved or unavailable
     * input rejects the successful callback result. This proves endpoint agreement, not that a
     * concurrently changed file could never have been restored between the two observations.
     */
    fun <Evidence> observeCurrent(
        capture: (InstalledGradleModelInputs) -> Refinement<Evidence, InstalledGradleModelCaptureFailure>,
    ): Refinement<Evidence, InstalledGradleModelCaptureFailure> {
        val before = when (val observation = current()) {
            is Refinement.Refined -> observation.value
            is Refinement.Rejected -> return observation
        }
        val captured = when (val observation = capture(before)) {
            is Refinement.Refined -> observation
            is Refinement.Rejected -> return observation
        }
        return when (val after = before.current()) {
            is Refinement.Refined -> captured
            is Refinement.Rejected -> after
        }
    }

    private data class ContentIdentity(val sha256: String)

    companion object {
        /**
         * Refines physical paths into content evidence owned by this import guard. Missing,
         * inaccessible, symlinked, or unsupported selected input entries fail closed.
         */
        fun capture(
            workspaceRoot: Path,
        ): Refinement<InstalledGradleModelInputs, InstalledGradleModelCaptureFailure> = try {
            if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS) ||
                workspaceRoot.toRealPath() != workspaceRoot
            ) {
                Refinement.Rejected(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE)
            } else {
                val entries = sortedMapOf<Path, ContentIdentity>()
                Files.walkFileTree(workspaceRoot, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult =
                        if (path != workspaceRoot && path.fileName.toString() in excludedDirectories) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                        val relative = workspaceRoot.relativize(path)
                        if (attributes.isSymbolicLink && path.fileName.toString() in modelInputDirectories) {
                            throw IOException("symlinked Gradle model input directory")
                        }
                        if (selected(relative)) {
                            if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                                throw IOException("unsupported Gradle model input")
                            }
                            val digest = MessageDigest.getInstance("SHA-256")
                            Files.newInputStream(path).use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    digest.update(buffer, 0, count)
                                }
                            }
                            entries[relative] = ContentIdentity(
                                digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                            )
                        }
                        return FileVisitResult.CONTINUE
                    }
                })
                Refinement.Refined(InstalledGradleModelInputs(workspaceRoot, entries.toMap()))
            }
        } catch (_: IOException) {
            Refinement.Rejected(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE)
        } catch (_: SecurityException) {
            Refinement.Rejected(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE)
        }

        private fun selected(path: Path): Boolean {
            val name = path.fileName.toString()
            return name.endsWith(".gradle") || name.endsWith(".gradle.kts") ||
                name == "gradle.properties" || name == "gradle-daemon-jvm.properties" ||
                name == "gradle-wrapper.properties" ||
                (name.endsWith(".toml") && path.any { it.toString() == "gradle" }) ||
                path.any { it.toString() == "buildSrc" || it.toString() == "build-logic" }
        }

        private val modelInputDirectories = setOf("gradle", "buildSrc", "build-logic")
        private val excludedDirectories = setOf(".git", ".gradle", ".idea", ".agent-turn", "build", "out")
    }
}
