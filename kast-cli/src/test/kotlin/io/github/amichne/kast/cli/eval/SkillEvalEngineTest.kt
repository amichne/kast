package io.github.amichne.kast.cli.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkillEvalEngineTest {

    private val target = SkillTarget(kind = "skill", name = "kast", path = ".agents/skills/kast")

    // --- Determinism ---

    @Test
    fun `same descriptor produces same result`() {
        val descriptor = descriptorWith(
            checks = listOf(passingCheck("c1"), failingCheck("c2", EvalSeverity.ERROR)),
        )
        val r1 = SkillEvalEngine.evaluate(descriptor)
        val r2 = SkillEvalEngine.evaluate(descriptor)
        assertEquals(r1.summary.score, r2.summary.score)
        assertEquals(r1.summary.grade, r2.summary.grade)
        assertEquals(r1.summary.deductions, r2.summary.deductions)
    }

    // --- Scoring ---

    @Test
    fun `all passing checks produce score 100 grade A`() {
        val descriptor = descriptorWith(
            checks = listOf(passingCheck("c1"), passingCheck("c2"), passingCheck("c3")),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertEquals(100, result.summary.score)
        assertEquals(EvalGrade.A, result.summary.grade)
        assertTrue(result.summary.deductions.isEmpty())
    }

    @Test
    fun `single error deducts 10 points`() {
        val descriptor = descriptorWith(
            checks = listOf(failingCheck("err1", EvalSeverity.ERROR)),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertEquals(90, result.summary.score)
        assertEquals(EvalGrade.B, result.summary.grade)
    }

    @Test
    fun `single warning deducts 3 points`() {
        val descriptor = descriptorWith(
            checks = listOf(warningCheck("w1")),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertEquals(97, result.summary.score)
        assertEquals(EvalGrade.A, result.summary.grade)
    }

    @Test
    fun `single info deducts 1 point`() {
        val descriptor = descriptorWith(
            checks = listOf(infoCheck("i1")),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertEquals(99, result.summary.score)
        assertEquals(EvalGrade.A, result.summary.grade)
    }

    @Test
    fun `score floors at 0`() {
        val checks = (1..15).map { failingCheck("err$it", EvalSeverity.ERROR) }
        val descriptor = descriptorWith(checks = checks)
        val result = SkillEvalEngine.evaluate(descriptor)
        assertEquals(0, result.summary.score)
        assertEquals(EvalGrade.F, result.summary.grade)
    }

    // --- Grade boundaries ---

    @Test
    fun `score 93 is grade A`() {
        assertEquals(EvalGrade.A, SkillEvalEngine.scoreToGrade(93))
    }

    @Test
    fun `score 92 is grade B`() {
        assertEquals(EvalGrade.B, SkillEvalEngine.scoreToGrade(92))
    }

    @Test
    fun `score 85 is grade B`() {
        assertEquals(EvalGrade.B, SkillEvalEngine.scoreToGrade(85))
    }

    @Test
    fun `score 84 is grade C`() {
        assertEquals(EvalGrade.C, SkillEvalEngine.scoreToGrade(84))
    }

    @Test
    fun `score 70 is grade C`() {
        assertEquals(EvalGrade.C, SkillEvalEngine.scoreToGrade(70))
    }

    @Test
    fun `score 69 is grade D`() {
        assertEquals(EvalGrade.D, SkillEvalEngine.scoreToGrade(69))
    }

    @Test
    fun `score 55 is grade D`() {
        assertEquals(EvalGrade.D, SkillEvalEngine.scoreToGrade(55))
    }

    @Test
    fun `score 54 is grade F`() {
        assertEquals(EvalGrade.F, SkillEvalEngine.scoreToGrade(54))
    }

    @Test
    fun `score 0 is grade F`() {
        assertEquals(EvalGrade.F, SkillEvalEngine.scoreToGrade(0))
    }

    // --- Budget bands ---

    @Test
    fun `trigger tokens below good threshold band GOOD`() {
        val budget = RawBudget(triggerTokens = 400, invokeTokens = 1000, deferredTokens = 5000)
        val banded = SkillEvalEngine.applyBudgetBands(budget)
        assertEquals(BudgetBand.GOOD, banded.triggerBand)
        assertEquals(BudgetBand.GOOD, banded.invokeBand)
        assertEquals(BudgetBand.GOOD, banded.deferredBand)
    }

    @Test
    fun `trigger tokens above fair threshold band POOR`() {
        val budget = RawBudget(triggerTokens = 1500, invokeTokens = 6000, deferredTokens = 30000)
        val banded = SkillEvalEngine.applyBudgetBands(budget)
        assertEquals(BudgetBand.POOR, banded.triggerBand)
        assertEquals(BudgetBand.POOR, banded.invokeBand)
        assertEquals(BudgetBand.POOR, banded.deferredBand)
    }

    @Test
    fun `trigger tokens at FAIR boundary band FAIR`() {
        val budget = RawBudget(triggerTokens = 750, invokeTokens = 3000, deferredTokens = 15000)
        val banded = SkillEvalEngine.applyBudgetBands(budget)
        assertEquals(BudgetBand.FAIR, banded.triggerBand)
        assertEquals(BudgetBand.FAIR, banded.invokeBand)
        assertEquals(BudgetBand.FAIR, banded.deferredBand)
    }

    // --- Compare ---

    @Test
    fun `compare detects score improvement and resolved failures`() {
        val before = SkillEvalEngine.evaluate(
            descriptorWith(checks = listOf(failingCheck("c1", EvalSeverity.ERROR), passingCheck("c2"))),
        )
        val after = SkillEvalEngine.evaluate(
            descriptorWith(checks = listOf(passingCheck("c1"), passingCheck("c2"))),
        )
        val comparison = SkillEvalEngine.compareResults(before, after)
        assertEquals(10, comparison.scoreDelta)
        assertTrue(comparison.resolvedFailures.contains("c1"))
        assertTrue(comparison.newFailures.isEmpty())
    }

    @Test
    fun `compare detects new failures`() {
        val before = SkillEvalEngine.evaluate(
            descriptorWith(checks = listOf(passingCheck("c1"))),
        )
        val after = SkillEvalEngine.evaluate(
            descriptorWith(checks = listOf(failingCheck("c1", EvalSeverity.ERROR))),
        )
        val comparison = SkillEvalEngine.compareResults(before, after)
        assertEquals(-10, comparison.scoreDelta)
        assertTrue(comparison.newFailures.contains("c1"))
    }

    @Test
    fun `compare detects budget changes`() {
        val before = SkillEvalEngine.evaluate(
            descriptorWith(budget = RawBudget(triggerTokens = 400, invokeTokens = 1000, deferredTokens = 5000)),
        )
        val after = SkillEvalEngine.evaluate(
            descriptorWith(budget = RawBudget(triggerTokens = 500, invokeTokens = 1200, deferredTokens = 5500)),
        )
        val comparison = SkillEvalEngine.compareResults(before, after)
        assertEquals(100, comparison.budgetDelta.triggerDelta)
        assertEquals(200, comparison.budgetDelta.invokeDelta)
        assertEquals(500, comparison.budgetDelta.deferredDelta)
    }

    // --- Check counts ---

    @Test
    fun `check counts are accurate`() {
        val descriptor = descriptorWith(
            checks = listOf(
                passingCheck("c1"),
                passingCheck("c2"),
                failingCheck("c3", EvalSeverity.ERROR),
                warningCheck("c4"),
            ),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertEquals(2, result.summary.checkCounts.pass)
        assertEquals(1, result.summary.checkCounts.fail)
        assertEquals(1, result.summary.checkCounts.warn)
        assertEquals(4, result.summary.checkCounts.total)
    }

    // --- Enrichment ---

    @Test
    fun `why bullets explain deductions`() {
        val descriptor = descriptorWith(
            checks = listOf(failingCheck("c1", EvalSeverity.ERROR), warningCheck("c2")),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertTrue(result.summary.whyBullets.any { it.contains("error-level") })
        assertTrue(result.summary.whyBullets.any { it.contains("warning-level") })
    }

    @Test
    fun `fix first points to worst deduction`() {
        val descriptor = descriptorWith(
            checks = listOf(warningCheck("w1"), failingCheck("e1", EvalSeverity.ERROR)),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertTrue(result.summary.fixFirst?.startsWith("e1:") ?: false)
    }

    @Test
    fun `watch next points to first warning`() {
        val descriptor = descriptorWith(
            checks = listOf(passingCheck("p1"), warningCheck("w1")),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertTrue(result.summary.watchNext?.startsWith("w1:") ?: false)
    }

    @Test
    fun `improvement brief lists all deductions`() {
        val descriptor = descriptorWith(
            checks = listOf(failingCheck("e1", EvalSeverity.ERROR), warningCheck("w1")),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertEquals(2, result.improvementBrief.findings.size)
    }

    @Test
    fun `measurement plan recommends re-run when failures exist`() {
        val descriptor = descriptorWith(
            checks = listOf(failingCheck("e1", EvalSeverity.ERROR)),
        )
        val result = SkillEvalEngine.evaluate(descriptor)
        assertTrue(result.measurementPlan.steps.any { it.contains("Re-run") })
    }

    // --- JSON serialization ---

    @Test
    fun `EvalResult serializes with schema version`() {
        val descriptor = descriptorWith(checks = listOf(passingCheck("c1")))
        val result = SkillEvalEngine.evaluate(descriptor)
        val json = io.github.amichne.kast.cli.defaultCliJson()
        val encoded = json.encodeToString(EvalResult.serializer(), result)
        assertTrue(encoded.contains("schema_version"))
        assertTrue(encoded.contains("summary"))
        assertTrue(encoded.contains("budgets"))
    }

    // --- Helpers ---

    private fun descriptorWith(
        checks: List<EvalCheck> = emptyList(),
        metrics: List<EvalMetric> = emptyList(),
        budget: RawBudget = RawBudget(triggerTokens = 300, invokeTokens = 1500, deferredTokens = 8000),
    ) = SkillDescriptor(
        target = target,
        checks = checks,
        metrics = metrics,
        budget = budget,
    )

    private fun passingCheck(id: String) = EvalCheck(
        id = id, category = "test", severity = EvalSeverity.INFO,
        status = EvalStatus.PASS, message = "$id passed",
    )

    private fun failingCheck(id: String, severity: EvalSeverity) = EvalCheck(
        id = id, category = "test", severity = severity,
        status = EvalStatus.FAIL, message = "$id failed",
    )

    private fun warningCheck(id: String) = EvalCheck(
        id = id, category = "test", severity = EvalSeverity.WARNING,
        status = EvalStatus.WARN, message = "$id warned",
    )

    private fun infoCheck(id: String) = EvalCheck(
        id = id, category = "test", severity = EvalSeverity.INFO,
        status = EvalStatus.WARN, message = "$id info warning",
    )
}
