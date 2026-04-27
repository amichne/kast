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

        skillDir.resolve("fixtures/maintenance/references/wrapper-openapi.yaml").deleteExisting()

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
