package io.github.amichne.kast.cli.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

class SkillWrapperInputTest {

    @Test
    fun resolveWorkspaceRootUsesExplicitValueWhenPresent() {
        val result = SkillWrapperInput.resolveWorkspaceRoot(
            explicit = "/explicit/ws",
            env = mapOf("KAST_WORKSPACE_ROOT" to "/env/ws"),
        )
        assertEquals(Path.of("/explicit/ws").toAbsolutePath().normalize().toString(), result)
    }

    @Test
    fun resolveWorkspaceRootIgnoresWorkspaceRootEnvWhenExplicitValueIsAbsent(@TempDir tempDir: Path) {
        val result = SkillWrapperInput.resolveWorkspaceRoot(
            explicit = null,
            env = mapOf("KAST_WORKSPACE_ROOT" to "/env/ws"),
            currentWorkingDirectory = tempDir,
        )
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), result)
    }

    @Test
    fun resolveWorkspaceRootFallsBackToCurrentWorkingDirectoryWhenEnvAbsent(@TempDir tempDir: Path) {
        val result = SkillWrapperInput.resolveWorkspaceRoot(
            explicit = null,
            env = emptyMap(),
            currentWorkingDirectory = tempDir,
        )
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), result)
    }

    @Test
    fun resolveWorkspaceRootTreatsBlankExplicitAsAbsent(@TempDir tempDir: Path) {
        val result = SkillWrapperInput.resolveWorkspaceRoot(
            explicit = "  ",
            env = mapOf("KAST_WORKSPACE_ROOT" to "/env/ws"),
            currentWorkingDirectory = tempDir,
        )
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), result)
    }

    @Test
    fun parseJsonInputReadsLiteralJson() {
        val input = """{"symbol":"foo"}"""
        val result = SkillWrapperInput.parseJsonInput(input)
        assertEquals(input, result)
    }

    @Test
    fun parseJsonInputReadsJsonFromFile(@TempDir tempDir: Path) {
        val jsonFile = tempDir.resolve("request.json")
        val content = """{"symbol":"bar"}"""
        jsonFile.toFile().writeText(content)
        val result = SkillWrapperInput.parseJsonInput(jsonFile.toString())
        assertEquals(content, result)
    }

    @Test
    fun parseJsonInputFailsOnNonJsonNonFileInput() {
        assertThrows<IllegalArgumentException> {
            SkillWrapperInput.parseJsonInput("not-json-and-not-a-file")
        }
    }
}
