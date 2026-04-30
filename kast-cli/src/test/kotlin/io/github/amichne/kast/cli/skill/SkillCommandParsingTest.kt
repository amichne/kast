package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.cli.tty.CliCommand
import io.github.amichne.kast.cli.tty.CliCommandParser
import io.github.amichne.kast.cli.tty.CliFailure
import io.github.amichne.kast.cli.tty.defaultCliJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SkillCommandParsingTest {

    private val parser = CliCommandParser(defaultCliJson())

    @Test
    fun `skill workspace-files parses with JSON literal`() {
        val json = """{"workspaceRoot":"/tmp/ws"}"""
        val command = parser.parse(arrayOf("skill", "workspace-files", json))
        val skill = command as CliCommand.Skill
        assertEquals(SkillWrapperName.WORKSPACE_FILES, skill.name)
        assertEquals(json, skill.rawInput)
    }

    @Test
    fun `skill resolve parses with JSON literal`() {
        val json = """{"symbol":"MyClass","kind":"class"}"""
        val command = parser.parse(arrayOf("skill", "resolve", json))
        val skill = command as CliCommand.Skill
        assertEquals(SkillWrapperName.RESOLVE, skill.name)
        assertEquals(json, skill.rawInput)
    }

    @Test
    fun `skill references parses with JSON literal`() {
        val json = """{"symbol":"foo","fileHint":"Bar.kt"}"""
        val command = parser.parse(arrayOf("skill", "references", json))
        val skill = command as CliCommand.Skill
        assertEquals(SkillWrapperName.REFERENCES, skill.name)
    }

    @Test
    fun `skill callers parses with JSON literal`() {
        val json = """{"symbol":"process","direction":"incoming"}"""
        val command = parser.parse(arrayOf("skill", "callers", json))
        val skill = command as CliCommand.Skill
        assertEquals(SkillWrapperName.CALLERS, skill.name)
    }

    @Test
    fun `skill diagnostics parses with JSON literal`() {
        val json = """{"filePaths":["src/Main.kt"]}"""
        val command = parser.parse(arrayOf("skill", "diagnostics", json))
        val skill = command as CliCommand.Skill
        assertEquals(SkillWrapperName.DIAGNOSTICS, skill.name)
    }

    @Test
    fun `skill rename parses with JSON literal`() {
        val json = """{"symbol":"OldName","newName":"NewName"}"""
        val command = parser.parse(arrayOf("skill", "rename", json))
        val skill = command as CliCommand.Skill
        assertEquals(SkillWrapperName.RENAME, skill.name)
    }

    @Test
    fun `skill scaffold parses with JSON literal`() {
        val json = """{"targetFile":"src/Foo.kt"}"""
        val command = parser.parse(arrayOf("skill", "scaffold", json))
        val skill = command as CliCommand.Skill
        assertEquals(SkillWrapperName.SCAFFOLD, skill.name)
    }

    @Test
    fun `skill write-and-validate parses with JSON literal`() {
        val json = """{"filePath":"src/New.kt","content":"class New"}"""
        val command = parser.parse(arrayOf("skill", "write-and-validate", json))
        val skill = command as CliCommand.Skill
        assertEquals(SkillWrapperName.WRITE_AND_VALIDATE, skill.name)
    }

    @Test
    fun `skill without wrapper name shows help`() {
        val command = parser.parse(arrayOf("skill"))
        assertNotNull(command as? CliCommand.Help)
    }

    @Test
    fun `skill with unknown wrapper name fails`() {
        val ex = assertThrows<CliFailure> {
            parser.parse(arrayOf("skill", "unknown-command", "{}"))
        }
        assertEquals("CLI_USAGE", ex.code)
    }

    @Test
    fun `skill wrapper without JSON arg fails`() {
        val ex = assertThrows<CliFailure> {
            parser.parse(arrayOf("skill", "resolve"))
        }
        assertEquals("CLI_USAGE", ex.code)
    }
}
