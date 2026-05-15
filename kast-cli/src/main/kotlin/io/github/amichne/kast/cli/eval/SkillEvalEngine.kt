package io.github.amichne.kast.cli.eval

/**
 * Deterministic evaluation engine that scores a [SkillDescriptor] and produces
 * an [EvalResult] with grade, deductions, budget bands, and improvement guidance.
 */
internal object SkillEvalEngine {

    private val GRADE_THRESHOLDS = listOf(
        93 to EvalGrade.A,
        85 to EvalGrade.B,
        70 to EvalGrade.C,
        55 to EvalGrade.D,
    )

    // Deduction points by severity
    private const val ERROR_DEDUCTION = 10
    private const val WARNING_DEDUCTION = 3
    private const val INFO_DEDUCTION = 1

    // Budget band thresholds (token counts)
    internal object BudgetThresholds {
        const val TRIGGER_GOOD = 500
        const val TRIGGER_FAIR = 1000
        const val INVOKE_GOOD = 2000
        const val INVOKE_FAIR = 5000
        const val DEFERRED_GOOD = 10000
        const val DEFERRED_FAIR = 25000
    }

    fun evaluate(descriptor: SkillDescriptor): EvalResult {
        val deductions = computeDeductions(descriptor.checks)
        val score = computeScore(deductions)
        val grade = scoreToGrade(score)
        val checkCounts = countChecks(descriptor.checks)
        val bandedBudget = applyBudgetBands(descriptor.budget)
        val whyBullets = buildWhyBullets(descriptor.checks, deductions)
        val fixFirst = findFixFirst(descriptor.checks, deductions)
        val watchNext = findWatchNext(descriptor.checks)

        val summary = EvalSummary(
            score = score,
            grade = grade,
            deductions = deductions,
            checkCounts = checkCounts,
            whyBullets = whyBullets,
            fixFirst = fixFirst,
            watchNext = watchNext,
        )

        val improvementBrief = buildImprovementBrief(descriptor.checks, deductions)
        val measurementPlan = buildMeasurementPlan(descriptor.checks)

        return EvalResult(
            target = descriptor.target,
            summary = summary,
            budgets = bandedBudget,
            checks = descriptor.checks,
            metrics = descriptor.metrics,
            improvementBrief = improvementBrief,
            measurementPlan = measurementPlan,
        )
    }

    fun compareResults(before: EvalResult, after: EvalResult): ComparisonResult {
        val beforeFailIds = before.checks.filter { it.status == EvalStatus.FAIL }.map { it.id }.toSet()
        val afterFailIds = after.checks.filter { it.status == EvalStatus.FAIL }.map { it.id }.toSet()

        return ComparisonResult(
            scoreDelta = after.summary.score - before.summary.score,
            gradeBefore = before.summary.grade,
            gradeAfter = after.summary.grade,
            resolvedFailures = (beforeFailIds - afterFailIds).sorted(),
            newFailures = (afterFailIds - beforeFailIds).sorted(),
            budgetDelta = BudgetDelta(
                triggerDelta = after.budgets.triggerTokens - before.budgets.triggerTokens,
                invokeDelta = after.budgets.invokeTokens - before.budgets.invokeTokens,
                deferredDelta = after.budgets.deferredTokens - before.budgets.deferredTokens,
            ),
        )
    }

    internal fun computeDeductions(checks: List<EvalCheck>): List<Deduction> =
        checks.filter { it.status != EvalStatus.PASS }.map { check ->
            Deduction(
                checkId = check.id,
                points = when (check.severity) {
                    EvalSeverity.ERROR -> ERROR_DEDUCTION
                    EvalSeverity.WARNING -> WARNING_DEDUCTION
                    EvalSeverity.INFO -> INFO_DEDUCTION
                },
                reason = check.message,
            )
        }

    internal fun computeScore(deductions: List<Deduction>): Int =
        (100 - deductions.sumOf { it.points }).coerceIn(0, 100)

    internal fun scoreToGrade(score: Int): EvalGrade =
        GRADE_THRESHOLDS.firstOrNull { score >= it.first }?.second ?: EvalGrade.F

    internal fun applyBudgetBands(budget: RawBudget): BandedBudget = BandedBudget(
        triggerTokens = budget.triggerTokens,
        triggerBand = tokenBand(budget.triggerTokens, BudgetThresholds.TRIGGER_GOOD, BudgetThresholds.TRIGGER_FAIR),
        invokeTokens = budget.invokeTokens,
        invokeBand = tokenBand(budget.invokeTokens, BudgetThresholds.INVOKE_GOOD, BudgetThresholds.INVOKE_FAIR),
        deferredTokens = budget.deferredTokens,
        deferredBand = tokenBand(budget.deferredTokens, BudgetThresholds.DEFERRED_GOOD, BudgetThresholds.DEFERRED_FAIR),
    )

    private fun tokenBand(tokens: Int, goodMax: Int, fairMax: Int): BudgetBand = when {
        tokens <= goodMax -> BudgetBand.GOOD
        tokens <= fairMax -> BudgetBand.FAIR
        else -> BudgetBand.POOR
    }

    private fun countChecks(checks: List<EvalCheck>): CheckCounts = CheckCounts(
        pass = checks.count { it.status == EvalStatus.PASS },
        fail = checks.count { it.status == EvalStatus.FAIL },
        warn = checks.count { it.status == EvalStatus.WARN },
        total = checks.size,
    )

    private fun buildWhyBullets(checks: List<EvalCheck>, deductions: List<Deduction>): List<String> {
        val bullets = mutableListOf<String>()
        val failCount = deductions.count { it.points == ERROR_DEDUCTION }
        val warnCount = deductions.count { it.points == WARNING_DEDUCTION }
        if (failCount > 0) bullets.add("$failCount error-level checks failed (-${failCount * ERROR_DEDUCTION} points)")
        if (warnCount > 0) bullets.add("$warnCount warning-level checks (-${warnCount * WARNING_DEDUCTION} points)")
        val passCount = checks.count { it.status == EvalStatus.PASS }
        if (passCount > 0) bullets.add("$passCount checks passed")
        return bullets
    }

    private fun findFixFirst(checks: List<EvalCheck>, deductions: List<Deduction>): String? {
        val worstDeduction = deductions.maxByOrNull { it.points } ?: return null
        return "${worstDeduction.checkId}: ${worstDeduction.reason}"
    }

    private fun findWatchNext(checks: List<EvalCheck>): String? =
        checks.firstOrNull { it.status == EvalStatus.WARN }?.let { "${it.id}: ${it.message}" }

    private fun buildImprovementBrief(checks: List<EvalCheck>, deductions: List<Deduction>): ImprovementBrief {
        val findings = deductions.map { "${it.checkId}: ${it.reason} (-${it.points})" }
        val prompt = if (findings.isEmpty()) {
            "All checks pass. Consider expanding coverage."
        } else {
            "Fix ${findings.size} issue(s): ${findings.first().substringBefore(" (-")}"
        }
        return ImprovementBrief(findings = findings, suggestedPrompt = prompt)
    }

    private fun buildMeasurementPlan(checks: List<EvalCheck>): MeasurementPlan {
        val steps = mutableListOf<String>()
        val failedIds = checks.filter { it.status == EvalStatus.FAIL }.map { it.id }
        if (failedIds.isNotEmpty()) {
            steps.add("Re-run eval after addressing: ${failedIds.joinToString()}")
        }
        steps.add("Run kast eval skill --compare=baseline.json to track regression")
        steps.add("Verify CI passes with updated score")
        return MeasurementPlan(steps = steps)
    }
}
