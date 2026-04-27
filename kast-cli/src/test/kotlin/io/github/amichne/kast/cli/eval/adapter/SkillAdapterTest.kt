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
    fun `scan does not require vendor-specific agent metadata`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        assertTrue(descriptor.checks.none { it.id == "structural-agent-interface-exists" })
        assertTrue(descriptor.checks.none { it.id == "structural-agent-interface-implicit-invocation" })
    }

    @Test
    fun `scan checks routing improvement assets`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val check = descriptor.checks.first { it.id == "structural-routing-improvement-assets" }
        assertEquals(EvalStatus.PASS, check.status)
    }

    @Test
    fun `scan validates checked-in eval corpora`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val behavior = descriptor.checks.first { it.id == "corpus-behavior-evals-valid" }
        val routing = descriptor.checks.first { it.id == "corpus-routing-evals-valid" }
        val coverage = descriptor.checks.first { it.id == "corpus-failure-mode-coverage" }
        assertEquals(EvalStatus.PASS, behavior.status)
        assertEquals(EvalStatus.PASS, routing.status)
        assertEquals(EvalStatus.PASS, coverage.status)
    }

    @Test
    fun `scan fails empty behavior eval corpus`() {
        val skillDir = createMinimalSkill()
        skillDir.resolve("fixtures/maintenance/evals/evals.json").writeText("""{"skill_name":"kast","evals":[]}""")
        val descriptor = SkillAdapter(skillDir).scan()
        val check = descriptor.checks.first { it.id == "corpus-behavior-evals-valid" }
        assertEquals(EvalStatus.FAIL, check.status)
        assertEquals(EvalSeverity.ERROR, check.severity)
    }

    @Test
    fun `scan fails malformed routing eval entries`() {
        val skillDir = createMinimalSkill()
        skillDir.resolve("fixtures/maintenance/evals/routing.json").writeText(
            """
            {"skill_name":"kast","suite":"routing","evals":[
              {"id":"bad-routing","prompt":"Trace this Kotlin flow","expected_skill":"ksat","expected_route":"kast","allowed_ops":["kast skill resolve","grep"],"forbidden_ops":["grep"],"failure_mode":"trigger_miss"}
            ]}
            """.trimIndent(),
        )
        val descriptor = SkillAdapter(skillDir).scan()
        val check = descriptor.checks.first { it.id == "corpus-routing-evals-valid" }
        assertEquals(EvalStatus.FAIL, check.status)
    }

    @Test
    fun `scan fails unknown failure modes`() {
        val skillDir = createMinimalSkill()
        writeBehaviorCorpus(skillDir, listOf("trigger_miss", "schema_reponse"))
        val descriptor = SkillAdapter(skillDir).scan()
        val check = descriptor.checks.first { it.id == "corpus-behavior-evals-valid" }
        assertEquals(EvalStatus.FAIL, check.status)
        assertTrue(check.message.contains("unknown failure_mode"))
    }

    @Test
    fun `scan warns when failure mode coverage is incomplete`() {
        val skillDir = createMinimalSkill()
        writeBehaviorCorpus(skillDir, listOf("trigger_miss"))
        writeRoutingCorpus(skillDir, listOf("trigger_miss", "routing_bypass"))
        val descriptor = SkillAdapter(skillDir).scan()
        val check = descriptor.checks.first { it.id == "corpus-failure-mode-coverage" }
        assertEquals(EvalStatus.WARN, check.status)
        assertTrue(check.message.contains("missing"))
    }

    @Test
    fun `scan flags lingering legacy artifacts`() {
        val skillDir = createMinimalSkill()
        skillDir.resolve("agents").createDirectories()
        skillDir.resolve("agents/kast.md").writeText("stale")
        val descriptor = SkillAdapter(skillDir).scan()
        val legacy = descriptor.checks.first { it.id == "structural-legacy-artifacts-removed" }
        assertEquals(EvalStatus.WARN, legacy.status)
        assertTrue(legacy.message.contains("agents/kast.md"))
    }

    @Test
    fun `scan passes legacy-artifact check when the tree is clean`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val legacy = descriptor.checks.first { it.id == "structural-legacy-artifacts-removed" }
        assertEquals(EvalStatus.PASS, legacy.status)
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
        assertEquals(0, descriptor.budget.invokeTokens, "invoke tokens should be zero without optional vendor metadata")
        assertTrue(descriptor.budget.deferredTokens > 0, "deferred tokens should be positive")
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
        assertTrue(metricIds.contains("corpus-behavior-eval-count"))
        assertTrue(metricIds.contains("corpus-routing-eval-count"))
        assertTrue(metricIds.contains("corpus-failure-mode-count"))
    }

    @Test
    fun `completeness checks cover all wrappers`() {
        val skillDir = createMinimalSkill()
        val descriptor = SkillAdapter(skillDir).scan()
        val completenessChecks = descriptor.checks.filter { it.category == "completeness" }
        assertEquals(9, completenessChecks.size, "Should have one completeness check per wrapper")
        assertTrue(completenessChecks.all { it.status == EvalStatus.PASS })
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
            kast skill metrics
            """.trimIndent(),
        )

        val refs = skillDir.resolve("references").createDirectories()
        refs.resolve("quickstart.md").writeText("# Quickstart\n")

        val maintenanceDir = skillDir.resolve("fixtures/maintenance")
        val maintenanceEvals = maintenanceDir.resolve("evals").createDirectories()
        writeBehaviorCorpus(skillDir, REQUIRED_FAILURE_MODES.take(4))
        writeRoutingCorpus(skillDir, REQUIRED_FAILURE_MODES.drop(4))
        val maintenanceRefs = maintenanceDir.resolve("references").createDirectories()
        maintenanceRefs.resolve("routing-improvement.md").writeText("# Routing improvement\n")
        maintenanceRefs.resolve("wrapper-openapi.yaml").writeText(
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
            x-command: kast skill metrics
            """.trimIndent(),
        )

        val maintenanceScripts = maintenanceDir.resolve("scripts").createDirectories()
        maintenanceScripts.resolve("build-routing-corpus.py").writeText(
            """
            #!/usr/bin/env python3
            print("ok")
            """.trimIndent(),
        )

        return skillDir
    }

    private fun writeBehaviorCorpus(skillDir: Path, failureModes: List<String>) {
        skillDir.resolve("fixtures/maintenance/evals/evals.json").writeText(
            buildString {
                append("""{"skill_name":"kast","evals":[""")
                failureModes.forEachIndexed { index, failureMode ->
                    if (index > 0) append(",")
                    append(
                        """
                        {"id":${index + 1},"prompt":"Behavior prompt ${index + 1}","expected_output":"Expected behavior ${index + 1}","files":[],"expectations":["Uses kast semantically"],"failure_mode":"$failureMode"}
                        """.trimIndent(),
                    )
                }
                append("]}")
            },
        )
    }

    private fun writeRoutingCorpus(skillDir: Path, failureModes: List<String>) {
        skillDir.resolve("fixtures/maintenance/evals/routing.json").writeText(
            buildString {
                append("""{"skill_name":"kast","suite":"routing","evals":[""")
                failureModes.forEachIndexed { index, failureMode ->
                    if (index > 0) append(",")
                    append(
                        """
                        {"id":"routing-${index + 1}","prompt":"Routing prompt ${index + 1}","expected_skill":"kast","expected_route":"@kast","allowed_ops":["kast skill resolve"],"forbidden_ops":["grep"],"failure_mode":"$failureMode"}
                        """.trimIndent(),
                    )
                }
                append("]}")
            },
        )
    }

    private companion object {
        private val REQUIRED_FAILURE_MODES = listOf(
            "trigger_miss",
            "routing_bypass",
            "initialization_friction",
            "maintenance_thrash",
            "schema_request",
            "schema_response",
            "mutation_abandonment",
            "failure_response_ignored",
        )
    }
}
