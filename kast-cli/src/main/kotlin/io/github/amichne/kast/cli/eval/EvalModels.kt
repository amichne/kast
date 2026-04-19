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
    @SerialName("trigger_tokens") val triggerTokens: Int,
    @SerialName("invoke_tokens") val invokeTokens: Int,
    @SerialName("deferred_tokens") val deferredTokens: Int,
)

@Serializable
data class BandedBudget(
    @SerialName("trigger_tokens") val triggerTokens: Int,
    @SerialName("trigger_band") val triggerBand: BudgetBand,
    @SerialName("invoke_tokens") val invokeTokens: Int,
    @SerialName("invoke_band") val invokeBand: BudgetBand,
    @SerialName("deferred_tokens") val deferredTokens: Int,
    @SerialName("deferred_band") val deferredBand: BudgetBand,
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
    @SerialName("check_counts") val checkCounts: CheckCounts,
    @SerialName("why_bullets") val whyBullets: List<String> = emptyList(),
    @SerialName("fix_first") val fixFirst: String? = null,
    @SerialName("watch_next") val watchNext: String? = null,
)

@Serializable
data class Deduction(
    @SerialName("check_id") val checkId: String,
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
    @SerialName("suggested_prompt") val suggestedPrompt: String,
)

@Serializable
data class MeasurementPlan(
    val steps: List<String>,
)

@Serializable
data class EvalResult(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val target: SkillTarget,
    val summary: EvalSummary,
    val budgets: BandedBudget,
    val checks: List<EvalCheck>,
    val metrics: List<EvalMetric>,
    @SerialName("improvement_brief") val improvementBrief: ImprovementBrief,
    @SerialName("measurement_plan") val measurementPlan: MeasurementPlan,
)

@Serializable
data class ComparisonResult(
    @SerialName("score_delta") val scoreDelta: Int,
    @SerialName("grade_before") val gradeBefore: EvalGrade,
    @SerialName("grade_after") val gradeAfter: EvalGrade,
    @SerialName("resolved_failures") val resolvedFailures: List<String>,
    @SerialName("new_failures") val newFailures: List<String>,
    @SerialName("budget_delta") val budgetDelta: BudgetDelta,
)

@Serializable
data class BudgetDelta(
    @SerialName("trigger_delta") val triggerDelta: Int,
    @SerialName("invoke_delta") val invokeDelta: Int,
    @SerialName("deferred_delta") val deferredDelta: Int,
)
