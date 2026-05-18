package io.github.amichne.kast.cli.eval.adapter

import io.github.amichne.kast.cli.eval.EvalCheck
import io.github.amichne.kast.cli.eval.EvalMetric
import io.github.amichne.kast.cli.eval.EvalSeverity
import io.github.amichne.kast.cli.eval.EvalStatus
import io.github.amichne.kast.cli.eval.RawBudget
import io.github.amichne.kast.cli.eval.SkillDescriptor
import io.github.amichne.kast.cli.eval.SkillTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.math.ceil

/**
 * Scans a skill directory and produces a [SkillDescriptor] containing
 * budget estimates and structural health checks.
 *
 * Corpus-level behavioral benchmarking is owned by root `evaluation/`; this
 * adapter focuses on skill-surface health: SKILL.md presence, trigger
 * phrases, native tool coverage, legacy artifact absence, and references/scripts.
 */
internal class SkillAdapter(private val skillDir: Path) {
    private val routingScriptPath = skillDir.resolve("scripts/build-routing-corpus.py")
    private val routingReferencePath = skillDir.resolve("references/routing-improvement.md")

    fun scan(): SkillDescriptor {
        val checks = mutableListOf<EvalCheck>()
        val metrics = mutableListOf<EvalMetric>()

        checks += checkSkillMdExists()
        checks += checkLegacyWrappersRemoved()
        checks += checkSkillMdHasTriggerPhrases()
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

    internal fun estimateTokens(path: Path): Int {
        if (!path.exists()) return 0
        return ceil(Files.size(path) / 4.0).toInt()
    }

    private fun sumTokensInDir(dir: Path): Int {
        if (!dir.exists()) return 0
        return Files.walk(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .mapToInt { estimateTokens(it) }
                .sum()
        }
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
                "Remove the legacy artifacts and rely on native `kast_*` tools and `kast rpc` for machine access"
            } else {
                null
            },
        )
    }

    private fun checkRoutingImprovementAssets(): EvalCheck {
        val routingScriptExists = routingScriptPath.exists()
        val routingReferenceExists = routingReferencePath.exists()
        val allPresent = routingScriptExists && routingReferenceExists
        return EvalCheck(
            id = "structural-routing-improvement-assets",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (allPresent) EvalStatus.PASS else EvalStatus.WARN,
            message = listOf(
                "routing-script=$routingScriptExists",
                "routing-reference=$routingReferenceExists",
            ).joinToString(),
            remediation = if (!allPresent) {
                "Add scripts/build-routing-corpus.py and references/routing-improvement.md"
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

    private fun checkWrapperCompleteness(): List<EvalCheck> {
        val skillMdText = skillDir.resolve("SKILL.md").takeIf(Path::exists)?.readText().orEmpty()
        return REQUIRED_NATIVE_TOOL_NAMES.toList().sorted().map { nativeTool ->
            val documentedInSkillMd = skillMdText.contains(nativeTool)
            EvalCheck(
                id = "completeness-native-tool-${nativeTool.removePrefix("kast_")}",
                category = "completeness",
                severity = EvalSeverity.INFO,
                status = if (documentedInSkillMd) EvalStatus.PASS else EvalStatus.WARN,
                message = "Native tool $nativeTool documentedInSkillMd=$documentedInSkillMd",
                remediation = if (documentedInSkillMd) {
                    null
                } else {
                    "Document `$nativeTool` in SKILL.md"
                },
            )
        }
    }

    // --- Budget metrics ---

    private fun budgetMetrics(budget: RawBudget): List<EvalMetric> = listOf(
        EvalMetric(
            id = "budget-trigger-tokens",
            category = "budget",
            value = budget.triggerTokens.toDouble(),
            unit = "tokens",
        ),
        EvalMetric(
            id = "budget-invoke-tokens",
            category = "budget",
            value = budget.invokeTokens.toDouble(),
            unit = "tokens",
        ),
        EvalMetric(
            id = "budget-deferred-tokens",
            category = "budget",
            value = budget.deferredTokens.toDouble(),
            unit = "tokens",
        ),
    )

    private companion object {
        private val REQUIRED_NATIVE_TOOL_NAMES = setOf(
            "kast_workspace_files",
            "kast_workspace_symbol",
            "kast_workspace_search",
            "kast_file_outline",
            "kast_scaffold",
            "kast_resolve",
            "kast_references",
            "kast_callers",
            "kast_metrics",
            "kast_diagnostics",
            "kast_rename",
            "kast_write_and_validate",
        )
    }
}
