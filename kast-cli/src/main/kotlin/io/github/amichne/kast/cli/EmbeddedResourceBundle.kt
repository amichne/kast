package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.CliFailure
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

internal abstract class EmbeddedResourceBundle(
    val version: String,
    protected val resourceRoot: String,
    val manifest: List<String>,
    val versionMarkerFileName: String,
    private val resourceReader: (String) -> InputStream?,
    private val missingResourceErrorCode: String,
    private val resourceDescription: String,
) {
    fun writeTree(targetDir: Path) {
        Files.createDirectories(targetDir)
        manifest.forEach { relativePath ->
            val targetPath = targetDir.resolve(relativePath)
            targetPath.parent?.let(Files::createDirectories)
            openResource(relativePath).use { input ->
                Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
            markExecutableIfNeeded(relativePath, targetPath)
        }
        Files.writeString(targetDir.resolve(versionMarkerFileName), "$version${System.lineSeparator()}")
    }

    private fun markExecutableIfNeeded(
        relativePath: String,
        targetPath: Path,
    ) {
        if (!isExecutableResource(relativePath)) return
        if (!targetPath.fileSystem.supportedFileAttributeViews().contains("posix")) return

        val permissions = Files.getPosixFilePermissions(targetPath).toMutableSet()
        permissions += PosixFilePermission.OWNER_EXECUTE
        permissions += PosixFilePermission.GROUP_EXECUTE
        permissions += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(targetPath, permissions)
    }

    private fun isExecutableResource(relativePath: String): Boolean =
        EXECUTABLE_RESOURCE_SUFFIXES.any(relativePath::endsWith)

    private fun openResource(relativePath: String): InputStream =
        resourceReader(relativePath)
        ?: throw CliFailure(
            code = missingResourceErrorCode,
            message = "Bundled $resourceDescription resource not found: /$resourceRoot/$relativePath",
        )

    private companion object {
        val EXECUTABLE_RESOURCE_SUFFIXES: Set<String> = setOf(".sh", ".mjs", ".py")
    }
}
