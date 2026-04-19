package io.github.amichne.kast.cli.eval

import io.github.amichne.kast.cli.CliFailure
import io.github.amichne.kast.cli.CliOutput
import io.github.amichne.kast.cli.EvalOutputFormat
import io.github.amichne.kast.cli.EvalSkillExecutor
import io.github.amichne.kast.cli.EvalSkillOptions
import io.github.amichne.kast.cli.defaultCliJson
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText

class EvalSkillCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = defaultCliJson()

    @Test
    fun `eval skill produces JSON with schema_version`() {
        val skillDir = createMinimalSkill()
        val executor = EvalSkillExecutor(json)
        val output = executor.execute(EvalSkillOptions(skillDir = skillDir))
        val text = (output as CliOutput.Text).value
        assertTrue(text.contains("schema_version"))
        assertTrue(text.contains("summary"))
        assertTrue(text.contains("budgets"))
    }

    @Test
    fun `eval skill markdown format produces readable output`() {
        val skillDir = createMinimalSkill()
        val executor = EvalSkillExecutor(json)
        val output = executor.execute(
            EvalSkillOptions(skillDir = skillDir, format = EvalOutputFormat.MARKDOWN),
        )
        val text = (output as CliOutput.Text).value
        assertTrue(text.contains("# Skill Evaluation:"))
        assertTrue(text.contains("Score:"))
        assertTrue(text.contains("Budget"))
    }

    @Test
    fun `eval skill compare with no regression returns output`() {
        val skillDir = createMinimalSkill()
        val executor = EvalSkillExecutor(json)

        val baselineOutput = executor.execute(EvalSkillOptions(skillDir = skillDir))
        val baselineText = (baselineOutput as CliOutput.Text).value
        val baselineFile = tempDir.resolve("baseline.json")
        baselineFile.writeText(baselineText)

        val output = executor.execute(
            EvalSkillOptions(skillDir = skillDir, compareBaseline = baselineFile),
        )
        val text = (output as CliOutput.Text).value
        assertTrue(text.contains("comparison"))
    }

    @Test
    fun `eval skill compare with regression throws`() {
        val skillDir = createMinimalSkill()
        val executor = EvalSkillExecutor(json)

        val baselineOutput = executor.execute(EvalSkillOptions(skillDir = skillDir))
        val baselineFile = tempDir.resolve("baseline.json")
        baselineFile.writeText((baselineOutput as CliOutput.Text).value)

        skillDir.resolve("agents/kast.md").deleteExisting()

        val failure = assertThrows(CliFailure::class.java) {
            executor.execute(
                EvalSkillOptions(skillDir = skillDir, compareBaseline = baselineFile),
            )
        }
        assertTrue(failure.code == "EVAL_SKILL_REGRESSION")
        assertTrue(failure.message.contains("regressed"))
    }

    @Test
    fun `eval skill on missing directory throws`() {
        val executor = EvalSkillExecutor(json)
        val failure = assertThrows(CliFailure::class.java) {
            executor.execute(
                EvalSkillOptions(skillDir = tempDir.resolve("nonexistent")),
            )
        }
        assertTrue(failure.code == "EVAL_SKILL_ERROR")
    }

    private fun createMinimalSkill(): Path {
        val skillDir = tempDir.resolve("kast").createDirectories()
        skillDir.resolve("SKILL.md").writeText(
            """
            description: test
            Trigger phrases: resolve, analyze
            kast skill resolve
            kast skill references
            kast skill callers
            kast skill diagnostics
            kast skill rename
            kast skill scaffold
            kast skill write-and-validate
            kast skill workspace-files
            """.trimIndent(),
        )

        val agents = skillDir.resolve("agents").createDirectories()
        agents.resolve("kast.md").writeText("# Kast Agent\nkast skill resolve\nkast skill diagnostics")
        agents.resolve("explore.md").writeText("# Explore Agent\nkast skill references")
        agents.resolve("plan.md").writeText("# Plan Agent\nkast skill callers")
        agents.resolve("edit.md").writeText("# Edit Agent\nkast skill write-and-validate")

        val scripts = skillDir.resolve("scripts").createDirectories()
        scripts.resolve("resolve-kast.sh").writeText("#!/bin/bash\nexit 0\n")

        val refs = skillDir.resolve("references").createDirectories()
        refs.resolve("wrapper-openapi.yaml").writeText(
            """
            openapi: '3.0.0'
            x-command: kast skill resolve
            x-command: kast skill references
            x-command: kast skill callers
            x-command: kast skill diagnostics
            x-command: kast skill rename
            x-command: kast skill scaffold
            x-command: kast skill write-and-validate
            x-command: kast skill workspace-files
            """.trimIndent(),
        )

        return skillDir
    }
}
