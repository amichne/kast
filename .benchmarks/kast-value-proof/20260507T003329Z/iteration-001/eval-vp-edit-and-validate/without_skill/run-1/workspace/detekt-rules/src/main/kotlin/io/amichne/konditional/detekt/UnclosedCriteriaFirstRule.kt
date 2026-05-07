package io.amichne.konditional.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression

class UnclosedCriteriaFirstRule(config: Config) : Rule(config) {

    override val issue: Issue = Issue(
        id = "UnclosedCriteriaFirstRule",
        severity = Severity.Defect,
        description = "Criteria-first rules must be completed with `yields(value)` or `yields { ... }`.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val isCriteriaFirstRule = expression.isCriteriaFirstRuleCall()
        if (!isCriteriaFirstRule) return

        val isClosed = expression.isClosedWithYields()
        if (isClosed) return

        val message =
            "Unclosed criteria-first rule. Use `rule { ... } yields value`, `rule { ... } yields { ... }`, " +
                "or `rule(value) { ... }`."

        report(CodeSmell(issue, Entity.from(expression), message))
    }
}

private fun KtCallExpression.isCriteriaFirstRuleCall(): Boolean =
    calleeExpression?.text == "rule" &&
        valueArguments.isEmpty() &&
        lambdaArguments.size == 1

private fun KtCallExpression.isClosedWithYields(): Boolean =
    isLeftOperandOfYieldsInfix() || isReceiverOfYieldsDotCall()

private fun KtCallExpression.isLeftOperandOfYieldsInfix(): Boolean {
    val (node, parent) = bubbleUpParentheses(this)
    val binary = parent as? KtBinaryExpression ?: return false
    return binary.left == node && binary.operationReference.text == "yields"
}

private fun KtCallExpression.isReceiverOfYieldsDotCall(): Boolean {
    val (node, parent) = bubbleUpParentheses(this)
    val dot = parent as? KtDotQualifiedExpression ?: return false
    val selectorCall = dot.selectorExpression as? KtCallExpression ?: return false
    return dot.receiverExpression == node && selectorCall.calleeExpression?.text == "yields"
}

private fun bubbleUpParentheses(expression: KtExpression): Pair<KtExpression, Any?> {
    var current: KtExpression = expression
    var parent = current.parent
    while (parent is KtParenthesizedExpression) {
        current = parent
        parent = current.parent
    }
    return current to parent
}
