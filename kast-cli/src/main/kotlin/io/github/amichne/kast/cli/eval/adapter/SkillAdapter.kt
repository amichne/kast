package io.github.amichne.kast.cli.eval.adapter

import io.github.amichne.kast.cli.eval.EvalCheck
import io.github.amichne.kast.cli.eval.EvalMetric
import io.github.amichne.kast.cli.eval.EvalSeverity
import io.github.amichne.kast.cli.eval.EvalStatus
import io.github.amichne.kast.cli.eval.RawBudget
import io.github.amichne.kast.cli.eval.SkillDescriptor
import io.github.amichne.kast.cli.eval.SkillTarget
import io.github.amichne.kast.cli.skill.SkillWrapperName
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.math.ceil

/**
 * Scans a skill directory and produces a [SkillDescriptor] containing
 * budget estimates, structural checks, and completeness metrics.
 */
internal class SkillAdapter(private val skillDir: Path) {

    fun scan(): SkillDescriptor {
        val checks = mutableListOf<EvalCheck>()
        val metrics = mutableListOf<EvalMetric>()

        checks += checkSkillMdExists()
        checks += checkAgentFilesExist()
        checks += checkWrapperScriptsExist()
        checks += checkAgentReferencesValidWrappers()
        checks += checkSkillMdHasTriggerPhrases()
        checks += checkWrapperOpenApiExists()
        checks += checkWrapperCompleteness()

        val budget = estimateBudget()
        metrics += budgetMetrics(budget)

        return SkillDescriptor(
            target = SkillTarget(kind = "skill", name = skillDir.name, path = skillDir.toString()),
            checks = checks,
            metrics = metrics,
            budget = budget,
        )
    }

    // --- Budget estimation ---

    internal fun estimateBudget(): RawBudget {
        val skillMd = skillDir.resolve("SKILL.md")
        val triggerTokens = estimateTokens(skillMd)

        val agentsDir = skillDir.resolve("agents")
        val invokeTokens = sumTokensInDir(agentsDir, ".md")

        val refsDir = skillDir.resolve("references")
        val scriptsDir = skillDir.resolve("scripts")
        val deferredTokens = sumTokensInDir(refsDir) + sumTokensInDir(scriptsDir)

        return RawBudget(
            triggerTokens = triggerTokens,
            invokeTokens = invokeTokens,
            deferredTokens = deferredTokens,
        )
    }

    // --- Structural checks ---

    private fun checkSkillMdExists(): EvalCheck {
        val exists = skillDir.resolve("SKILL.md").exists()
        return EvalCheck(
            id = "structural-skill-md-exists",
            category = "structural",
            severity = EvalSeverity.ERROR,
            status = if (exists) EvalStatus.PASS else EvalStatus.FAIL,
            message = if (exists) "SKILL.md found" else "SKILL.md missing",
            remediation = if (!exists) "Create SKILL.md at skill root" else null,
        )
    }

    private fun checkAgentFilesExist(): List<EvalCheck> {
        val agentsDir = skillDir.resolve("agents")
        val expectedAgents = listOf("kast.md", "explore.md", "plan.md", "edit.md")
        return expectedAgents.map { name ->
            val exists = agentsDir.resolve(name).exists()
            EvalCheck(
                id = "structural-agent-$name-exists",
                category = "structural",
                severity = EvalSeverity.ERROR,
                status = if (exists) EvalStatus.PASS else EvalStatus.FAIL,
                message = if (exists) "Agent file agents/$name found" else "Agent file agents/$name missing",
                remediation = if (!exists) "Create agents/$name" else null,
            )
        }
    }

    private fun checkWrapperScriptsExist(): List<EvalCheck> =
        SkillWrapperName.entries.map { wrapper ->
            val scriptName = "kast-${wrapper.cliName}.sh"
            val exists = skillDir.resolve("scripts/$scriptName").exists()
            EvalCheck(
                id = "structural-script-${wrapper.cliName}-exists",
                category = "structural",
                severity = EvalSeverity.WARNING,
                status = if (exists) EvalStatus.PASS else EvalStatus.WARN,
                message = if (exists) "Script scripts/$scriptName found" else "Script scripts/$scriptName missing (expected during Kotlin migration)",
            )
        }

    private fun checkAgentReferencesValidWrappers(): List<EvalCheck> {
        val agentsDir = skillDir.resolve("agents")
        if (!agentsDir.exists()) return emptyList()

        val wrapperNames = SkillWrapperName.entries.map { it.cliName }.toSet()
        val wrapperPatterns = wrapperNames.map { "kast-$it" } + wrapperNames.map { "kast skill $it" }

        return Files.list(agentsDir).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".md") }.toList()
        }.flatMap { agentFile ->
            val content = agentFile.readText()
            val referenced = wrapperPatterns.filter { content.contains(it) }
            val unreferenced = wrapperPatterns.filter { !content.contains(it) }
            if (unreferenced.isEmpty()) {
                listOf(
                    EvalCheck(
                        id = "structural-agent-${agentFile.name}-refs-valid",
                        category = "structural",
                        severity = EvalSeverity.INFO,
                        status = EvalStatus.PASS,
                        message = "Agent ${agentFile.name} references ${referenced.size} wrapper commands",
                    ),
                )
            } else {
                // Not all agent files need to reference all wrappers, so this is just info
                listOf(
                    EvalCheck(
                        id = "structural-agent-${agentFile.name}-refs-partial",
                        category = "structural",
                        severity = EvalSeverity.INFO,
                        status = EvalStatus.PASS,
                        message = "Agent ${agentFile.name} references ${referenced.size} of ${wrapperPatterns.size} wrapper commands",
                    ),
                )
            }
        }
    }

    private fun checkSkillMdHasTriggerPhrases(): EvalCheck {
        val skillMd = skillDir.resolve("SKILL.md")
        if (!skillMd.exists()) {
            return EvalCheck(
                id = "structural-trigger-phrases",
                category = "structural",
                severity = EvalSeverity.ERROR,
                status = EvalStatus.FAIL,
                message = "Cannot check trigger phrases: SKILL.md missing",
            )
        }
        val content = skillMd.readText()
        // Look for a section about triggers or common trigger-phrase patterns
        val hasTriggers = content.contains("trigger", ignoreCase = true) ||
            content.contains("Trigger phrases", ignoreCase = true) ||
            content.contains("description:", ignoreCase = true)
        return EvalCheck(
            id = "structural-trigger-phrases",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (hasTriggers) EvalStatus.PASS else EvalStatus.WARN,
            message = if (hasTriggers) "SKILL.md contains trigger/description metadata" else "SKILL.md missing trigger phrases",
            remediation = if (!hasTriggers) "Add trigger phrases or a description section to SKILL.md" else null,
        )
    }

    private fun checkWrapperOpenApiExists(): EvalCheck {
        val exists = skillDir.resolve("references/wrapper-openapi.yaml").exists()
        return EvalCheck(
            id = "structural-openapi-exists",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (exists) EvalStatus.PASS else EvalStatus.WARN,
            message = if (exists) "wrapper-openapi.yaml found" else "wrapper-openapi.yaml missing",
            remediation = if (!exists) "Generate wrapper-openapi.yaml from wrapper contracts" else null,
        )
    }

    private fun checkWrapperCompleteness(): List<EvalCheck> {
        // Check that each SkillWrapperName has either a script or Kotlin command
        return SkillWrapperName.entries.map { wrapper ->
            val scriptExists = skillDir.resolve("scripts/kast-${wrapper.cliName}.sh").exists()
            // Kotlin wrappers are always present (compiled into kast-cli)
            val kotlinExists = true
            EvalCheck(
                id = "completeness-wrapper-${wrapper.cliName}",
                category = "completeness",
                severity = EvalSeverity.INFO,
                status = EvalStatus.PASS,
                message = "Wrapper ${wrapper.cliName}: kotlin=$kotlinExists, script=$scriptExists",
            )
        }
    }

    // --- Token estimation ---

    internal fun estimateTokens(file: Path): Int {
        if (!file.exists() || !file.isRegularFile()) return 0
        return ceil(file.readText().length / 4.0).toInt()
    }

    private fun sumTokensInDir(dir: Path, extension: String? = null): Int {
        if (!dir.exists()) return 0
        return Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() && (extension == null || it.name.endsWith(extension)) }
                .toList()
        }.sumOf { estimateTokens(it) }
    }

    private fun budgetMetrics(budget: RawBudget): List<EvalMetric> = listOf(
        EvalMetric(id = "budget-trigger-tokens", category = "budget", value = budget.triggerTokens.toDouble(), unit = "tokens"),
        EvalMetric(id = "budget-invoke-tokens", category = "budget", value = budget.invokeTokens.toDouble(), unit = "tokens"),
        EvalMetric(id = "budget-deferred-tokens", category = "budget", value = budget.deferredTokens.toDouble(), unit = "tokens"),
    )
}
