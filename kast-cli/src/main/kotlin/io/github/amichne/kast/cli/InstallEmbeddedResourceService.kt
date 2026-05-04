package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.CliFailure
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal abstract class InstallEmbeddedResourceService<O, R>(
    private val bundle: EmbeddedResourceBundle,
    private val errorCode: String,
    private val installedDescription: String,
    private val cwdProvider: () -> Path = { Path.of(System.getProperty("user.dir", ".")) },
) {
    fun install(options: O): R {
        val request = installRequest(options, cwdProvider())
        val targetPath = request.targetPath.toAbsolutePath().normalize()
        val currentVersion = bundle.version

        targetPath.parent?.let(Files::createDirectories)
        when {
            Files.isSymbolicLink(targetPath) -> {
                if (!request.force) {
                    throw existingTargetFailure(targetPath)
                }
                deletePathRecursively(targetPath)
            }

            Files.isDirectory(targetPath) -> {
                val existingVersion = readInstalledVersion(targetPath)
                if (existingVersion == currentVersion) {
                    return result(
                        installedAt = targetPath.toString(),
                        version = currentVersion,
                        skipped = true,
                    )
                }
                if (!request.force) {
                    throw existingTargetFailure(targetPath)
                }
                deletePathRecursively(targetPath)
            }

            Files.exists(targetPath) -> {
                if (!request.force) {
                    throw existingTargetFailure(targetPath)
                }
                deletePathRecursively(targetPath)
            }
        }

        bundle.writeTree(targetPath)
        return result(
            installedAt = targetPath.toString(),
            version = currentVersion,
            skipped = false,
        )
    }

    protected abstract fun installRequest(
        options: O,
        cwd: Path,
    ): InstallEmbeddedResourceRequest

    protected abstract fun result(
        installedAt: String,
        version: String,
        skipped: Boolean,
    ): R

    private fun readInstalledVersion(targetPath: Path): String? {
        val markerPath = targetPath.resolve(bundle.versionMarkerFileName)
        if (!Files.isRegularFile(markerPath)) {
            return null
        }
        return Files.readString(markerPath)
            .trim()
            .takeIf(String::isNotEmpty)
    }

    private fun existingTargetFailure(targetPath: Path): CliFailure =
        CliFailure(
            code = errorCode,
            message = "Packaged $installedDescription already exists at $targetPath; rerun with --yes=true to overwrite it",
        )

    private fun deletePathRecursively(path: Path) {
        if (Files.isSymbolicLink(path) || Files.isRegularFile(path)) {
            Files.deleteIfExists(path)
            return
        }

        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) {
                        throw exc
                    }
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}

internal data class InstallEmbeddedResourceRequest(
    val targetPath: Path,
    val force: Boolean,
)
