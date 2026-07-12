package io.github.amichne.kast.idea.proofloss

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.NonEmptyList
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.shared.proofloss.ir.*
import io.github.amichne.kast.shared.proofloss.model.*
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import java.nio.file.Path

internal class IdeaProofIrExtractor(private val model: ProofModel) : ProofIrExtractor<KtNamedFunction> {
    override fun extract(source: KtNamedFunction): ExtractionResult {
        val id = functionId(source); val reasons = mutableListOf<UnsupportedReason>()
        if (source.bodyExpression != null && source.bodyBlockExpression == null) {
            reasons += UnsupportedReason.UnsupportedControlFlow(span(source.bodyExpression!!))
        }
        val body = lowerBlock(source.bodyBlockExpression, reasons)
        return if (reasons.isEmpty()) ExtractionResult.Supported(FunctionIr(id, source.valueParameters.mapTo(mutableSetOf(), ::valueId), body))
        else ExtractionResult.Unsupported(id, NonEmptyList(reasons.toList()))
    }

    private fun lowerBlock(block: KtBlockExpression?, reasons: MutableList<UnsupportedReason>): Block =
        Block(block?.statements.orEmpty().mapNotNull { lower(it, reasons) })

    private fun lower(expression: KtExpression, reasons: MutableList<UnsupportedReason>): Statement? {
        if (expression !is KtLambdaExpression && PsiTreeUtil.findChildOfType(expression, KtLambdaExpression::class.java) != null) {
            reasons += UnsupportedReason.NestedLambda(span(expression))
            return null
        }
        return when (expression) {
        is KtProperty -> lowerProperty(expression, reasons)
        is KtIfExpression -> lowerIf(expression, reasons)
        is KtReturnExpression -> ExitStatement(ExitKind.RETURN, span(expression))
        is KtThrowExpression -> ExitStatement(ExitKind.THROW, span(expression))
        is KtLoopExpression -> null.also { reasons += UnsupportedReason.Loop(span(expression)) }
        is KtCallExpression -> lowerCall(expression, reasons)
        is KtLambdaExpression -> null.also { reasons += UnsupportedReason.NestedLambda(span(expression)) }
            else -> NoOpStatement(span(expression))
        }
    }

    private fun lowerProperty(property: KtProperty, reasons: MutableList<UnsupportedReason>): Statement? {
        val target = valueId(property)
        if (property.isVar) { reasons += UnsupportedReason.MutableTrackedValue(span(property), target); return null }
        val initializer = property.initializer ?: return NoOpStatement(span(property))
        val valueExpression = when (initializer) {
            is KtNameReferenceExpression -> ValueExpression.Alias(resolveValue(initializer) ?: return null.also { reasons += UnsupportedReason.UnresolvedCall(span(initializer)) })
            is KtCallExpression -> materialize(initializer, reasons)
            is KtBinaryExpression -> if (initializer.operationToken == KtTokens.ELVIS && initializer.right is KtExpression) {
                val right = initializer.right
                if (right !is KtReturnExpression && right !is KtThrowExpression) null.also { reasons += UnsupportedReason.UnprovenMaterializationSuccess(span(initializer)) }
                else (initializer.left as? KtCallExpression)?.let { materialize(it, reasons) }
            } else null
            else -> null
        } ?: return null.also { reasons += UnsupportedReason.UnsupportedControlFlow(span(initializer)) }
        return LetStatement(target, valueExpression, span(property))
    }

    private fun materialize(call: KtCallExpression, reasons: MutableList<UnsupportedReason>): ValueExpression? {
        val key = call.toProofCallableKey() ?: return null.also { reasons += UnsupportedReason.UnresolvedCall(span(call)) }
        val predicate = model.predicateForMaterializer(key) ?: return null
        val argument = call.valueArguments.singleOrNull()?.getArgumentExpression() as? KtNameReferenceExpression
            ?: return null.also { reasons += UnsupportedReason.UnsupportedArgumentMapping(span(call)) }
        return ValueExpression.Materialize(predicate.id, resolveValue(argument) ?: return null.also { reasons += UnsupportedReason.UnresolvedCall(span(argument)) }, key)
    }

    private fun lowerIf(expression: KtIfExpression, reasons: MutableList<UnsupportedReason>): Statement? {
        var condition = expression.condition ?: return null
        var positive = true
        if (condition is KtPrefixExpression && condition.operationToken == KtTokens.EXCL) { positive = false; condition = condition.baseExpression ?: return null }
        val call = condition as? KtCallExpression ?: return null.also { reasons += UnsupportedReason.UnsupportedControlFlow(span(condition)) }
        val key = call.toProofCallableKey() ?: return null.also { reasons += UnsupportedReason.UnresolvedCall(span(call)) }
        val predicate = model.predicateForCallable(key)
            ?: return null.also { reasons += UnsupportedReason.UnsupportedControlFlow(span(expression)) }
        val argument = call.valueArguments.getOrNull(predicate.subjectArgumentIndex.value)?.getArgumentExpression() as? KtNameReferenceExpression
            ?: return null.also { reasons += UnsupportedReason.UnsupportedArgumentMapping(span(call)) }
        val subject = resolveValue(argument) ?: return null.also { reasons += UnsupportedReason.UnresolvedCall(span(argument)) }
        return IfStatement(PredicateCondition(predicate.id, subject, positive, span(call)), lowerBranch(expression.then, reasons), lowerBranch(expression.`else`, reasons))
    }

    private fun lowerBranch(expression: KtExpression?, reasons: MutableList<UnsupportedReason>): Block = when (expression) {
        null -> Block()
        is KtBlockExpression -> lowerBlock(expression, reasons)
        else -> Block(listOfNotNull(lower(expression, reasons)))
    }

    private fun lowerCall(call: KtCallExpression, reasons: MutableList<UnsupportedReason>): Statement? {
        if (PsiTreeUtil.findChildOfType(call, KtLambdaExpression::class.java) != null) {
            reasons += UnsupportedReason.NestedLambda(span(call))
            return null
        }
        val key = call.toProofCallableKey() ?: return null.also { reasons += UnsupportedReason.UnresolvedCall(span(call)) }
        val boundary = model.boundaryForCallable(key) ?: return NoOpStatement(span(call))
        val arguments = mutableMapOf<ArgumentIndex, TrackedValueId>()
        boundary.obligations.forEach { obligation ->
            val valueArgument = call.valueArguments.getOrNull(obligation.argumentIndex.value)
            if (valueArgument == null || valueArgument.isNamed() || valueArgument.getSpreadElement() != null) {
                reasons += UnsupportedReason.UnsupportedArgumentMapping(span(call)); return null
            }
            val reference = valueArgument.getArgumentExpression() as? KtNameReferenceExpression
                ?: return null.also { reasons += UnsupportedReason.NonDirectBoundaryArgument(span(call)) }
            arguments[obligation.argumentIndex] = resolveValue(reference)
                ?: return null.also { reasons += UnsupportedReason.UnresolvedCall(span(reference)) }
        }
        return BoundaryCall(boundary.id, arguments, span(call))
    }

    private fun resolveValue(reference: KtNameReferenceExpression): TrackedValueId? =
        (reference.mainReference.resolve() as? KtDeclaration)?.let(::valueId)
    private fun valueId(declaration: KtDeclaration) = TrackedValueId(path(declaration), SourceOffset.valid(declaration.textOffset))
    private fun functionId(function: KtNamedFunction) = ProofFunctionId(path(function), SourceOffset.valid(function.textOffset))
    private fun span(element: KtElement) = ProofSourceSpan(path(element), SourceOffset.valid(element.textRange.startOffset), SourceOffset.valid(element.textRange.endOffset))
    private fun path(element: KtElement) = NormalizedPath.ofAbsolute(Path.of(element.containingKtFile.virtualFilePath))
}
