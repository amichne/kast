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
    private val maintenanceDir = skillDir.resolve("fixtures/maintenance")
    private val wrapperOpenApiPath = maintenanceDir.resolve("references/wrapper-openapi.yaml")
    private val routingEvalPath = maintenanceDir.resolve("evals/routing.json")
    private val routingScriptPath = maintenanceDir.resolve("scripts/build-routing-corpus.py")
    private val routingReferencePath = maintenanceDir.resolve("references/routing-improvement.md")

    fun scan(): SkillDescriptor {
        val checks = mutableListOf<EvalCheck>()
        val metrics = mutableListOf<EvalMetric>()

        checks += checkSkillMdExists()
        checks += checkLegacyWrappersRemoved()
        checks += checkSkillMdHasTriggerPhrases()
        checks += checkWrapperOpenApiExists()
        checks += checkRoutingImprovementAssets()
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
        val invokeTokens = sumTokensInDir(agentsDir)

        val refsDir = skillDir.resolve("references")
        val deferredTokens = sumTokensInDir(refsDir)

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

    private fun checkLegacyWrappersRemoved(): EvalCheck {
        val legacyPaths = buildList {
            listOf("kast.md", "explore.md", "plan.md", "edit.md").forEach {
                add(skillDir.resolve("agents/$it"))
            }
        }
        val present = legacyPaths
            .filter(Path::exists)
            .map { skillDir.relativize(it).toString() }
            .sorted()
        return EvalCheck(
            id = "structural-legacy-artifacts-removed",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (present.isEmpty()) EvalStatus.PASS else EvalStatus.WARN,
            message = if (present.isEmpty()) {
                "Legacy shell-wrapper and sub-agent artifacts are absent"
            } else {
                "Legacy artifacts still present: ${present.joinToString()}"
            },
            remediation = if (present.isNotEmpty()) {
                "Remove the legacy artifacts and rely on native `kast skill` subcommands invoked via \$KAST_CLI_PATH"
            } else {
                null
            },
        )
    }

    private fun checkRoutingImprovementAssets(): EvalCheck {
        val routingEvalExists = routingEvalPath.exists()
        val routingScriptExists = routingScriptPath.exists()
        val routingReferenceExists = routingReferencePath.exists()
        val allPresent = routingEvalExists && routingScriptExists && routingReferenceExists
        return EvalCheck(
            id = "structural-routing-improvement-assets",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (allPresent) EvalStatus.PASS else EvalStatus.WARN,
            message = listOf(
                "routing-evals=$routingEvalExists",
                "routing-script=$routingScriptExists",
                "routing-reference=$routingReferenceExists",
            ).joinToString(),
            remediation = if (!allPresent) {
                "Add fixtures/maintenance/evals/routing.json, " +
                    "fixtures/maintenance/scripts/build-routing-corpus.py, and " +
                    "fixtures/maintenance/references/routing-improvement.md"
            } else {
                null
            },
        )
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
        val exists = wrapperOpenApiPath.exists()
        return EvalCheck(
            id = "structural-openapi-exists",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (exists) EvalStatus.PASS else EvalStatus.WARN,
            message = if (exists) "wrapper-openapi.yaml found" else "wrapper-openapi.yaml missing",
            remediation = if (!exists) {
                "Generate fixtures/maintenance/references/wrapper-openapi.yaml from wrapper contracts"
            } else {
                null
            },
        )
    }

    private fun checkWrapperCompleteness(): List<EvalCheck> {
        val skillMdText = skillDir.resolve("SKILL.md").takeIf(Path::exists)?.readText().orEmpty()
        val openApiText = wrapperOpenApiPath.takeIf(Path::exists)?.readText().orEmpty()
        return SkillWrapperName.entries.map { wrapper ->
            val command = "kast skill ${wrapper.cliName}"
            val documentedInSkillMd = skillMdText.contains(command)
            val documentedInOpenApi = openApiText.contains(command)
            EvalCheck(
                id = "completeness-wrapper-${wrapper.cliName}",
                category = "completeness",
                severity = EvalSeverity.INFO,
                status = if (documentedInSkillMd && documentedInOpenApi) EvalStatus.PASS else EvalStatus.WARN,
                message = "Wrapper ${wrapper.cliName}: skillMd=$documentedInSkillMd, openApi=$documentedInOpenApi",
                remediation = if (documentedInSkillMd && documentedInOpenApi) {
                    null
                } else {
                    "Document `$command` in both SKILL.md and fixtures/maintenance/references/wrapper-openapi.yaml"
                },
            )
        }
    }

    // --- Token estimation ---

    internal fun estimateTokens(file: Path): Int {
        if (!file.exists() || !file.isRegularFile()) return 0
        return ceil(file.readText().length / 4.0).toInt()
    }

    private fun sumTokensInDir(dir: Path): Int {
        if (!dir.exists()) return 0
        return Files.list(dir).use { stream ->
            stream.filter(Path::isRegularFile).toList()
        }.sumOf { estimateTokens(it) }
    }

    private fun budgetMetrics(budget: RawBudget): List<EvalMetric> = listOf(
        EvalMetric(id = "budget-trigger-tokens", category = "budget", value = budget.triggerTokens.toDouble(), unit = "tokens"),
        EvalMetric(id = "budget-invoke-tokens", category = "budget", value = budget.invokeTokens.toDouble(), unit = "tokens"),
        EvalMetric(id = "budget-deferred-tokens", category = "budget", value = budget.deferredTokens.toDouble(), unit = "tokens"),
    )
}
