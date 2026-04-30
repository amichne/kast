package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.eval.ComparisonResult
import io.github.amichne.kast.cli.eval.EvalResult
import io.github.amichne.kast.cli.eval.SkillEvalEngine
import io.github.amichne.kast.cli.eval.adapter.SkillAdapter
import io.github.amichne.kast.cli.tty.CliFailure
import io.github.amichne.kast.cli.tty.CliOutput
import io.github.amichne.kast.cli.tty.EvalOutputFormat
import io.github.amichne.kast.cli.tty.EvalSkillOptions
import kotlinx.serialization.json.Json
import kotlin.io.path.readText

/**
 * Executes the `kast eval skill` command: scans a skill directory,
 * evaluates it, and optionally compares against a baseline.
 */
internal class EvalSkillExecutor(private val json: Json) {

    fun execute(options: EvalSkillOptions): CliOutput {
        if (!options.skillDir.toFile().isDirectory) {
            throw CliFailure(
                code = "EVAL_SKILL_ERROR",
                message = "Skill directory does not exist: ${options.skillDir}",
            )
        }

        val adapter = SkillAdapter(options.skillDir)
        val descriptor = adapter.scan()
        val result = SkillEvalEngine.evaluate(descriptor)

        if (options.compareBaseline != null) {
            return executeComparison(result, options)
        }

        return formatResult(result, options.format)
    }

    private fun executeComparison(result: EvalResult, options: EvalSkillOptions): CliOutput {
        val baselinePath = options.compareBaseline!!
        if (!baselinePath.toFile().isFile) {
            throw CliFailure(
                code = "EVAL_SKILL_ERROR",
                message = "Baseline file does not exist: $baselinePath",
            )
        }
        val baselineText = baselinePath.readText()
        val baseline = json.decodeFromString(EvalResult.serializer(), baselineText)
        val comparison = SkillEvalEngine.compareResults(baseline, result)

        val output = when (options.format) {
            EvalOutputFormat.JSON -> {
                val combined = json.encodeToString(
                    ComparisonOutputSerializer,
                    ComparisonOutput(current = result, comparison = comparison),
                )
                CliOutput.Text(combined)
            }
            EvalOutputFormat.MARKDOWN -> CliOutput.Text(renderComparisonMarkdown(result, comparison))
        }

        if (comparison.scoreDelta < 0) {
            throw CliFailure(
                code = "EVAL_SKILL_REGRESSION",
                message = "Score regressed by ${-comparison.scoreDelta} points " +
                          "(${baseline.summary.grade} → ${result.summary.grade}). " +
                          "New failures: ${comparison.newFailures.joinToString()}",
            )
        }

        return output
    }

    private fun formatResult(result: EvalResult, format: EvalOutputFormat): CliOutput = when (format) {
        EvalOutputFormat.JSON -> CliOutput.Text(json.encodeToString(EvalResult.serializer(), result))
        EvalOutputFormat.MARKDOWN -> CliOutput.Text(renderMarkdown(result))
    }

    private fun renderMarkdown(result: EvalResult): String = buildString {
        appendLine("# Skill Evaluation: ${result.target.name}")
        appendLine()
        appendLine("**Score:** ${result.summary.score}/100 (${result.summary.grade})")
        appendLine()
        appendLine("## Budget")
        appendLine("| Bucket | Tokens | Band |")
        appendLine("|--------|--------|------|")
        appendLine("| Trigger | ${result.budgets.triggerTokens} | ${result.budgets.triggerBand} |")
        appendLine("| Invoke | ${result.budgets.invokeTokens} | ${result.budgets.invokeBand} |")
        appendLine("| Deferred | ${result.budgets.deferredTokens} | ${result.budgets.deferredBand} |")
        appendLine()
        if (result.summary.deductions.isNotEmpty()) {
            appendLine("## Deductions")
            result.summary.deductions.forEach {
                appendLine("- **${it.checkId}**: ${it.reason} (-${it.points})")
            }
            appendLine()
        }
        if (result.summary.whyBullets.isNotEmpty()) {
            appendLine("## Why")
            result.summary.whyBullets.forEach { appendLine("- $it") }
            appendLine()
        }
        result.summary.fixFirst?.let {
            appendLine("## Fix First")
            appendLine(it)
            appendLine()
        }
        appendLine("## Checks (${result.summary.checkCounts.pass}/${result.summary.checkCounts.total} pass)")
        result.checks.forEach {
            val icon = when (it.status) {
                io.github.amichne.kast.cli.eval.EvalStatus.PASS -> "✅"
                io.github.amichne.kast.cli.eval.EvalStatus.FAIL -> "❌"
                io.github.amichne.kast.cli.eval.EvalStatus.WARN -> "⚠️"
            }
            appendLine("- $icon ${it.id}: ${it.message}")
        }
    }

    private fun renderComparisonMarkdown(result: EvalResult, comparison: ComparisonResult): String = buildString {
        appendLine("# Skill Evaluation Comparison")
        appendLine()
        appendLine("**Score:** ${result.summary.score}/100 (${comparison.gradeBefore} → ${comparison.gradeAfter}, delta: ${comparison.scoreDelta})")
        appendLine()
        if (comparison.resolvedFailures.isNotEmpty()) {
            appendLine("## Resolved Failures ✅")
            comparison.resolvedFailures.forEach { appendLine("- $it") }
            appendLine()
        }
        if (comparison.newFailures.isNotEmpty()) {
            appendLine("## New Failures ❌")
            comparison.newFailures.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## Budget Delta")
        appendLine("| Bucket | Delta |")
        appendLine("|--------|-------|")
        appendLine("| Trigger | ${comparison.budgetDelta.triggerDelta} |")
        appendLine("| Invoke | ${comparison.budgetDelta.invokeDelta} |")
        appendLine("| Deferred | ${comparison.budgetDelta.deferredDelta} |")
    }
}

@kotlinx.serialization.Serializable
internal data class ComparisonOutput(
    val current: EvalResult,
    val comparison: ComparisonResult,
)

internal val ComparisonOutputSerializer = ComparisonOutput.serializer()
