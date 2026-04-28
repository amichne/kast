package io.github.amichne.kast.cli.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EvalSeverity { ERROR, WARNING, INFO }

@Serializable
enum class EvalStatus { PASS, FAIL, WARN }

@Serializable
enum class BudgetBand { GOOD, FAIR, POOR }

@Serializable
enum class EvalGrade { A, B, C, D, F }

@Serializable
data class EvalCheck(
    val id: String,
    val category: String,
    val severity: EvalSeverity,
    val status: EvalStatus,
    val message: String,
    val remediation: String? = null,
    val evidence: String? = null,
)

@Serializable
data class EvalMetric(
    val id: String,
    val category: String,
    val value: Double,
    val unit: String? = null,
    val description: String? = null,
)

@Serializable
data class RawBudget(
    val triggerTokens: Int,
    val invokeTokens: Int,
    val deferredTokens: Int,
)

@Serializable
data class BandedBudget(
    val triggerTokens: Int,
    val triggerBand: BudgetBand,
    val invokeTokens: Int,
    val invokeBand: BudgetBand,
    val deferredTokens: Int,
    val deferredBand: BudgetBand,
)

@Serializable
data class SkillTarget(
    val kind: String,
    val name: String,
    val path: String,
)

@Serializable
data class SkillDescriptor(
    val target: SkillTarget,
    val checks: List<EvalCheck>,
    val metrics: List<EvalMetric>,
    val budget: RawBudget,
)

@Serializable
data class EvalSummary(
    val score: Int,
    val grade: EvalGrade,
    val deductions: List<Deduction>,
    val checkCounts: CheckCounts,
    val whyBullets: List<String> = emptyList(),
    val fixFirst: String? = null,
    val watchNext: String? = null,
)

@Serializable
data class Deduction(
    val checkId: String,
    val points: Int,
    val reason: String,
)

@Serializable
data class CheckCounts(
    val pass: Int,
    val fail: Int,
    val warn: Int,
    val total: Int,
)

@Serializable
data class ImprovementBrief(
    val findings: List<String>,
    val suggestedPrompt: String,
)

@Serializable
data class MeasurementPlan(
    val steps: List<String>,
)

@Serializable
data class EvalResult(
    val schemaVersion: Int = 1,
    val target: SkillTarget,
    val summary: EvalSummary,
    val budgets: BandedBudget,
    val checks: List<EvalCheck>,
    val metrics: List<EvalMetric>,
    val improvementBrief: ImprovementBrief,
    val measurementPlan: MeasurementPlan,
)

@Serializable
data class ComparisonResult(
    val scoreDelta: Int,
    val gradeBefore: EvalGrade,
    val gradeAfter: EvalGrade,
    val resolvedFailures: List<String>,
    val newFailures: List<String>,
    val budgetDelta: BudgetDelta,
)

@Serializable
data class BudgetDelta(
    val triggerDelta: Int,
    val invokeDelta: Int,
    val deferredDelta: Int,
)
