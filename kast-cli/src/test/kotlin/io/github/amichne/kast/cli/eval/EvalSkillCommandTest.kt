package io.github.amichne.kast.cli.eval

import io.github.amichne.kast.cli.CliCommand
import io.github.amichne.kast.cli.EvalOutputFormat
import io.github.amichne.kast.cli.EvalSkillExecutor
import io.github.amichne.kast.cli.EvalSkillOptions
import io.github.amichne.kast.cli.defaultCliJson
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class EvalSkillCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = defaultCliJson()

    @Test
    fun `eval skill produces JSON with schema_version`() {
        val skillDir = createMinimalSkill()
        val executor = EvalSkillExecutor(json)
        val output = executor.execute(
            EvalSkillOptions(skillDir = skillDir),
        )
        val text = (output as io.github.amichne.kast.cli.CliOutput.Text).value
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
        val text = (output as io.github.amichne.kast.cli.CliOutput.Text).value
        assertTrue(text.contains("# Skill Evaluation:"))
        assertTrue(text.contains("Score:"))
        assertTrue(text.contains("Budget"))
    }

    @Test
    fun `eval skill compare with no regression returns output`() {
        val skillDir = createMinimalSkill()
        val executor = EvalSkillExecutor(json)

        // First run: create baseline
        val baselineOutput = executor.execute(EvalSkillOptions(skillDir = skillDir))
        val baselineText = (baselineOutput as io.github.amichne.kast.cli.CliOutput.Text).value
        val baselineFile = tempDir.resolve("baseline.json")
        baselineFile.writeText(baselineText)

        // Second run: compare
        val output = executor.execute(
            EvalSkillOptions(skillDir = skillDir, compareBaseline = baselineFile),
        )
        val text = (output as io.github.amichne.kast.cli.CliOutput.Text).value
        assertTrue(text.contains("comparison"))
    }

    @Test
    fun `eval skill compare with regression throws`() {
        val skillDir = createMinimalSkill()
        val executor = EvalSkillExecutor(json)

        // First run: create a "good" baseline with all agents
        val baselineOutput = executor.execute(EvalSkillOptions(skillDir = skillDir))
        val baselineFile = tempDir.resolve("baseline.json")
        baselineFile.writeText((baselineOutput as io.github.amichne.kast.cli.CliOutput.Text).value)

        // Regress: remove an agent file to cause a failure
        skillDir.resolve("agents/kast.md").toFile().delete()

        try {
            executor.execute(
                EvalSkillOptions(skillDir = skillDir, compareBaseline = baselineFile),
            )
            assertFalse(true, "Expected CliFailure for regression")
        } catch (e: io.github.amichne.kast.cli.CliFailure) {
            assertTrue(e.code == "EVAL_SKILL_REGRESSION")
            assertTrue(e.message.contains("regressed"))
        }
    }

    @Test
    fun `eval skill on missing directory throws`() {
        val executor = EvalSkillExecutor(json)
        try {
            executor.execute(
                EvalSkillOptions(skillDir = tempDir.resolve("nonexistent")),
            )
            assertFalse(true, "Expected CliFailure for missing dir")
        } catch (e: io.github.amichne.kast.cli.CliFailure) {
            assertTrue(e.code == "EVAL_SKILL_ERROR")
        }
    }

    private fun createMinimalSkill(): Path {
        val skillDir = tempDir.resolve("kast").createDirectories()
        skillDir.resolve("SKILL.md").writeText("description: test\nTrigger phrases: resolve, analyze")

        val agents = skillDir.resolve("agents").createDirectories()
        agents.resolve("kast.md").writeText("# Kast Agent\nkast-resolve.sh\nkast skill resolve")
        agents.resolve("explore.md").writeText("# Explore Agent\nkast-references.sh")
        agents.resolve("plan.md").writeText("# Plan Agent\nkast-callers.sh")
        agents.resolve("edit.md").writeText("# Edit Agent\nkast-write-and-validate.sh")

        val scripts = skillDir.resolve("scripts").createDirectories()
        scripts.resolve("kast-resolve.sh").writeText("#!/bin/bash\n# resolve")
        scripts.resolve("kast-references.sh").writeText("#!/bin/bash\n# references")
        scripts.resolve("kast-callers.sh").writeText("#!/bin/bash\n# callers")
        scripts.resolve("kast-diagnostics.sh").writeText("#!/bin/bash\n# diagnostics")
        scripts.resolve("kast-rename.sh").writeText("#!/bin/bash\n# rename")
        scripts.resolve("kast-scaffold.sh").writeText("#!/bin/bash\n# scaffold")
        scripts.resolve("kast-write-and-validate.sh").writeText("#!/bin/bash\n# w-a-v")
        scripts.resolve("kast-workspace-files.sh").writeText("#!/bin/bash\n# ws-files")

        val refs = skillDir.resolve("references").createDirectories()
        refs.resolve("wrapper-openapi.yaml").writeText("openapi: '3.0.0'")

        return skillDir
    }
}
