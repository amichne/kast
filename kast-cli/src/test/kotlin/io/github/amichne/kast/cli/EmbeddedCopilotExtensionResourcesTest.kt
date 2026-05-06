package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

class EmbeddedCopilotExtensionResourcesTest {
    @Test
    fun manifestAccountsForEverySourceCopilotExtensionFile() {
        val repoRoot = findRepoRoot(Path.of("").toAbsolutePath())
        val githubRoot = repoRoot.resolve(".github")
        val manifest = EmbeddedCopilotExtensionResources.MANIFEST.toSet()
        val excludedSourceFiles = EmbeddedCopilotExtensionResources.EXCLUDED_SOURCE_FILES
        val unpackagedFiles = listOf("agents", "hooks", "extensions")
            .flatMap { auditedDir ->
                githubRoot.resolve(auditedDir).sourceFilesRelativeTo(githubRoot)
            }
            .filterNot { sourcePath -> sourcePath in manifest || sourcePath in excludedSourceFiles }
            .sorted()

        assertTrue(
            unpackagedFiles.isEmpty(),
            "Add these .github files to EmbeddedCopilotExtensionResources.MANIFEST or EXCLUDED_SOURCE_FILES:\n" +
                unpackagedFiles.joinToString("\n"),
        )
    }

    private fun Path.sourceFilesRelativeTo(root: Path): List<String> = Files.walk(this).use { stream ->
        stream
            .filter { sourcePath -> Files.isRegularFile(sourcePath) }
            .map { sourcePath -> root.relativize(sourcePath).invariantSeparators() }
            .toList()
    }

    private fun Path.invariantSeparators(): String = toString().replace(File.separatorChar, '/')

    private fun findRepoRoot(start: Path): Path = generateSequence(start.normalize()) { it.parent }
        .firstOrNull { candidate -> Files.isRegularFile(candidate.resolve("kast-cli/build.gradle.kts")) }
        ?: error("Could not locate repo root from " + start)

}
