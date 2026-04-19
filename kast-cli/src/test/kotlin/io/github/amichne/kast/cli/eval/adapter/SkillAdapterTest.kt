package io.github.amichne.kast.cli.eval.adapter

import io.github.amichne.kast.cli.eval.EvalSeverity
import io.github.amichne.kast.cli.eval.EvalStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SkillAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `scan produces descriptor with target info`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        assertEquals("skill", descriptor.target.kind)
        assertEquals(skillDir.fileName.toString(), descriptor.target.name)
    }

    @Test
    fun `scan detects SKILL md presence`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val check = descriptor.checks.first { it.id == "structural-skill-md-exists" }
        assertEquals(EvalStatus.PASS, check.status)
    }

    @Test
    fun `scan detects SKILL md absence`() {
        val skillDir = tempDir.resolve("empty-skill").createDirectories()
        val descriptor = SkillAdapter(skillDir).scan()
        val check = descriptor.checks.first { it.id == "structural-skill-md-exists" }
        assertEquals(EvalStatus.FAIL, check.status)
        assertEquals(EvalSeverity.ERROR, check.severity)
    }

    @Test
    fun `scan detects agent files`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val kastAgent = descriptor.checks.first { it.id == "structural-agent-kast.md-exists" }
        assertEquals(EvalStatus.PASS, kastAgent.status)
        val editAgent = descriptor.checks.first { it.id == "structural-agent-edit.md-exists" }
        assertEquals(EvalStatus.PASS, editAgent.status)
    }

    @Test
    fun `scan flags missing agent files`() {
        val skillDir = tempDir.resolve("partial-skill").createDirectories()
        skillDir.resolve("SKILL.md").writeText("description: test")
        val descriptor = SkillAdapter(skillDir).scan()
        val kastAgent = descriptor.checks.first { it.id == "structural-agent-kast.md-exists" }
        assertEquals(EvalStatus.FAIL, kastAgent.status)
    }

    @Test
    fun `scan checks wrapper scripts`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val resolveScript = descriptor.checks.first { it.id == "structural-script-resolve-exists" }
        assertEquals(EvalStatus.PASS, resolveScript.status)
    }

    @Test
    fun `scan checks wrapper openapi`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val openApi = descriptor.checks.first { it.id == "structural-openapi-exists" }
        assertEquals(EvalStatus.PASS, openApi.status)
    }

    @Test
    fun `scan checks trigger phrases in SKILL md`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val triggers = descriptor.checks.first { it.id == "structural-trigger-phrases" }
        assertEquals(EvalStatus.PASS, triggers.status)
    }

    @Test
    fun `scan estimates token budget`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        assertTrue(descriptor.budget.triggerTokens > 0, "trigger tokens should be positive")
        assertTrue(descriptor.budget.invokeTokens > 0, "invoke tokens should be positive")
    }

    @Test
    fun `token estimation uses ceil of length div 4`() {
        val skillDir = createMinimalSkill()
        val adapter = SkillAdapter(skillDir)
        val skillMd = skillDir.resolve("SKILL.md")
        val expectedTokens = kotlin.math.ceil(skillMd.toFile().readText().length / 4.0).toInt()
        assertEquals(expectedTokens, adapter.estimateTokens(skillMd))
    }

    @Test
    fun `budget metrics are present`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val metricIds = descriptor.metrics.map { it.id }
        assertTrue(metricIds.contains("budget-trigger-tokens"))
        assertTrue(metricIds.contains("budget-invoke-tokens"))
        assertTrue(metricIds.contains("budget-deferred-tokens"))
    }

    @Test
    fun `completeness checks cover all wrappers`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val completenessChecks = descriptor.checks.filter { it.category == "completeness" }
        assertEquals(8, completenessChecks.size, "Should have one completeness check per wrapper")
    }

    @Test
    fun `scan on real skill directory succeeds`() {
        val realSkillDir = Path.of(".agents/skills/kast")
        if (realSkillDir.toAbsolutePath().toFile().exists()) {
            val descriptor = SkillAdapter(realSkillDir.toAbsolutePath()).scan()
            assertTrue(descriptor.checks.isNotEmpty())
            assertTrue(descriptor.budget.triggerTokens > 0)
        }
    }

    // --- Helper ---

    private fun createMinimalSkill(): Path {
        val skillDir = tempDir.resolve("kast").createDirectories()
        skillDir.resolve("SKILL.md").writeText("description: test\nTrigger phrases: resolve, analyze")

        val agents = skillDir.resolve("agents").createDirectories()
        agents.resolve("kast.md").writeText("# Kast Agent\nUse kast-resolve.sh for resolution\nkast skill resolve")
        agents.resolve("explore.md").writeText("# Explore Agent\nkast-references.sh")
        agents.resolve("plan.md").writeText("# Plan Agent\nkast-callers.sh")
        agents.resolve("edit.md").writeText("# Edit Agent\nkast-write-and-validate.sh")

        val scripts = skillDir.resolve("scripts").createDirectories()
        scripts.resolve("kast-resolve.sh").writeText("#!/bin/bash\n# resolve wrapper")
        scripts.resolve("kast-references.sh").writeText("#!/bin/bash\n# references wrapper")
        scripts.resolve("kast-callers.sh").writeText("#!/bin/bash\n# callers wrapper")
        scripts.resolve("kast-diagnostics.sh").writeText("#!/bin/bash\n# diagnostics wrapper")
        scripts.resolve("kast-rename.sh").writeText("#!/bin/bash\n# rename wrapper")
        scripts.resolve("kast-scaffold.sh").writeText("#!/bin/bash\n# scaffold wrapper")
        scripts.resolve("kast-write-and-validate.sh").writeText("#!/bin/bash\n# write-and-validate wrapper")
        scripts.resolve("kast-workspace-files.sh").writeText("#!/bin/bash\n# workspace-files wrapper")

        val refs = skillDir.resolve("references").createDirectories()
        refs.resolve("wrapper-openapi.yaml").writeText("openapi: '3.0.0'\ninfo:\n  title: Kast Wrappers")

        return skillDir
    }
}
