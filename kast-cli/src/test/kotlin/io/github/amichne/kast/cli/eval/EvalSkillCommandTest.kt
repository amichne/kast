package io.github.amichne.kast.cli.eval

import io.github.amichne.kast.cli.tty.CliFailure
import io.github.amichne.kast.cli.tty.CliOutput
import io.github.amichne.kast.cli.tty.EvalOutputFormat
import io.github.amichne.kast.cli.EvalSkillExecutor
import io.github.amichne.kast.cli.tty.EvalSkillOptions
import io.github.amichne.kast.cli.tty.defaultCliJson
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
    fun `eval skill produces JSON with schemaVersion`() {
        val skillDir = createMinimalSkill()
        val executor = EvalSkillExecutor(json)
        val output = executor.execute(EvalSkillOptions(skillDir = skillDir))
        val text = (output as CliOutput.Text).value
        assertTrue(text.contains("schemaVersion"))
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

        skillDir.resolve("references/wrapper-openapi.yaml").deleteExisting()

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
            kast_resolve
            kast_references
            kast_callers
            kast_diagnostics
            kast_rename
            kast_scaffold
            kast_write_and_validate
            kast_workspace_files
            """.trimIndent(),
        )

        val refs = skillDir.resolve("references").createDirectories()
        refs.resolve("quickstart.md").writeText("# Quickstart\n")

        val evalsDir = skillDir.resolve("evals").createDirectories()
        evalsDir.resolve("files").createDirectories()
        evalsDir.resolve("files/.gitkeep").writeText("")
        evalsDir.resolve("pain_points.jsonl").writeText("")
        writeCatalog(skillDir, REQUIRED_FAILURE_MODES.take(4), REQUIRED_FAILURE_MODES.drop(4))

        val historyDir = skillDir.resolve("history").createDirectories()
        historyDir.resolve("progression.json").writeText(
            """
            {"skill_name":"kast","updated_at":"2026-05-02T00:00:00Z","benchmarks":[],"case_history":{}}
            """.trimIndent(),
        )

        refs.resolve("routing-improvement.md").writeText("# Routing improvement\n")
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

        val scriptsDir = skillDir.resolve("scripts").createDirectories()
        scriptsDir.resolve("build-routing-corpus.py").writeText(
            """
            #!/usr/bin/env python3
            print("ok")
            """.trimIndent(),
        )

        return skillDir
    }

    private fun writeCatalog(skillDir: Path, behaviorFailureModes: List<String>, routingFailureModes: List<String>) {
        skillDir.resolve("evals/catalog.json").writeText(
            buildString {
                append("""{"skill_name":"kast","version":1,"cases":[""")
                var first = true
                behaviorFailureModes.forEachIndexed { index, failureMode ->
                    if (!first) append(",")
                    first = false
                    append(
                        """
                        {"id":"behavior-${index + 1}","title":"Behavior case ${index + 1}","prompt":"Behavior prompt ${index + 1}","files":[],"expected_output":"Expected behavior ${index + 1}","expectations":["Uses kast semantically"],"labels":["behavior","$failureMode"],"stage":"holdout","suite":"behavior","failure_mode":"$failureMode","source":{"kind":"test-fixture"},"promotion":{"required_pass_rate":1.0,"required_benchmarks":2}}
                        """.trimIndent(),
                    )
                }
                routingFailureModes.forEachIndexed { index, failureMode ->
                    if (!first) append(",")
                    first = false
                    append(
                        """
                        {"id":"routing-${index + 1}","title":"Routing case ${index + 1}","prompt":"Routing prompt ${index + 1}","files":[],"expected_output":"Routes through native kast tools","expectations":["Uses kast semantically"],"labels":["routing","$failureMode"],"stage":"holdout","suite":"routing","failure_mode":"$failureMode","expected_skill":"kast","expected_route":"native-kast-tools","allowed_ops":["kast_resolve"],"forbidden_ops":["grep","view"],"measurement_dimensions":["discoverability"],"source":{"kind":"test-fixture"},"promotion":{"required_pass_rate":1.0,"required_benchmarks":2}}
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
            "relative_path",
            "ambiguous_symbol",
            "schema_response",
            "mutation_abandonment",
            "failure_response_ignored",
        )
    }
}
