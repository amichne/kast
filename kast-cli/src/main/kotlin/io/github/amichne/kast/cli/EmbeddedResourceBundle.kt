package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.CliFailure
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
        }
        Files.writeString(targetDir.resolve(versionMarkerFileName), "$version${System.lineSeparator()}")
    }

    private fun openResource(relativePath: String): InputStream =
        resourceReader(relativePath)
            ?: throw CliFailure(
                code = missingResourceErrorCode,
                message = "Bundled $resourceDescription resource not found: /$resourceRoot/$relativePath",
            )
}
