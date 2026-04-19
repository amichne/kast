package io.github.amichne.kast.cli.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

class SkillWrapperInputTest {

    @Test
    fun `resolveWorkspaceRoot uses explicit value when present`() {
        val result = SkillWrapperInput.resolveWorkspaceRoot(
            explicit = "/explicit/ws",
            env = mapOf("KAST_WORKSPACE_ROOT" to "/env/ws"),
        )
        assertEquals("/explicit/ws", result)
    }

    @Test
    fun `resolveWorkspaceRoot falls back to KAST_WORKSPACE_ROOT env`() {
        val result = SkillWrapperInput.resolveWorkspaceRoot(
            explicit = null,
            env = mapOf("KAST_WORKSPACE_ROOT" to "/env/ws"),
        )
        assertEquals("/env/ws", result)
    }

    @Test
    fun `resolveWorkspaceRoot falls back to empty string when env absent`() {
        val result = SkillWrapperInput.resolveWorkspaceRoot(
            explicit = null,
            env = emptyMap(),
        )
        assertEquals("", result)
    }

    @Test
    fun `resolveWorkspaceRoot treats blank explicit as absent`() {
        val result = SkillWrapperInput.resolveWorkspaceRoot(
            explicit = "  ",
            env = mapOf("KAST_WORKSPACE_ROOT" to "/env/ws"),
        )
        assertEquals("/env/ws", result)
    }

    @Test
    fun `parseJsonInput reads literal JSON`() {
        val input = """{"symbol":"foo"}"""
        val result = SkillWrapperInput.parseJsonInput(input)
        assertEquals(input, result)
    }

    @Test
    fun `parseJsonInput reads JSON from file`(@TempDir tempDir: Path) {
        val jsonFile = tempDir.resolve("request.json")
        val content = """{"symbol":"bar"}"""
        jsonFile.toFile().writeText(content)
        val result = SkillWrapperInput.parseJsonInput(jsonFile.toString())
        assertEquals(content, result)
    }

    @Test
    fun `parseJsonInput fails on non-JSON non-file input`() {
        assertThrows<IllegalArgumentException> {
            SkillWrapperInput.parseJsonInput("not-json-and-not-a-file")
        }
    }
}
