package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.defaultCliJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class EmbeddedCopilotExtensionResourcesTest {
    @TempDir
    lateinit var tempDir: Path

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

    @Test
    fun manifestExplicitlyPackagesPortableHookResources() {
        val manifest = EmbeddedCopilotExtensionResources.MANIFEST.toSet()
        val excludedSourceFiles = EmbeddedCopilotExtensionResources.EXCLUDED_SOURCE_FILES

        assertTrue("hooks/export-session.py" in manifest)
        assertTrue("hooks/skill-shadowing.json" in manifest)
        assertFalse("hooks/export-session.py" in excludedSourceFiles)
        assertFalse("hooks/skill-shadowing.json" in excludedSourceFiles)
    }

    @Test
    fun skillShadowingJsonTracksExtensionBackedSkills() {
        val repoRoot = findRepoRoot(Path.of("").toAbsolutePath())
        val sourceSkills = parseSkillShadowing(repoRoot.resolve(".github/hooks/skill-shadowing.json"))

        assertEquals(listOf("kast", "kotlin-gradle-loop"), sourceSkills.map(ShadowedSkill::id))
        assertTrue(sourceSkills.all { it.shadowingExtensionId != null })

        val targetDir = tempDir.resolve(".github")
        EmbeddedCopilotExtensionResources(version = "test").writeCopilotExtensionTree(targetDir)

        val packagedSkills = parseSkillShadowing(targetDir.resolve("hooks/skill-shadowing.json"))
        assertEquals(listOf("kast", "kotlin-gradle-loop"), packagedSkills.map(ShadowedSkill::id))
        assertTrue(packagedSkills.all { it.shadowingExtensionId != null })
    }

    private fun Path.sourceFilesRelativeTo(root: Path): List<String> = Files.walk(this).use { stream ->
        stream
            .filter { sourcePath -> Files.isRegularFile(sourcePath) }
            .map { sourcePath -> root.relativize(sourcePath).invariantSeparators() }
            .toList()
    }

    private fun parseSkillShadowing(path: Path): List<ShadowedSkill> {
        val skills = defaultCliJson()
            .parseToJsonElement(Files.readString(path))
            .jsonObject
            .getValue("skills")
            .jsonArray
        return skills.map { skill ->
            val json = skill.jsonObject
            ShadowedSkill(
                id = json.getValue("id").jsonPrimitive.content,
                shadowingExtensionId = json["shadowingExtensionId"]?.jsonPrimitive?.content,
            )
        }
    }

    private fun Path.invariantSeparators(): String = toString().replace(File.separatorChar, '/')

    private fun findRepoRoot(start: Path): Path = generateSequence(start.normalize()) { it.parent }
                                                      .firstOrNull { candidate ->
                                                          Files.isRegularFile(
                                                              candidate.resolve(
                                                                  "kast-cli/build.gradle.kts"
                                                              )
                                                          )
                                                      }
                                                  ?: error("Could not locate repo root from " + start)

    private data class ShadowedSkill(
        val id: String,
        val shadowingExtensionId: String?,
    )
}
