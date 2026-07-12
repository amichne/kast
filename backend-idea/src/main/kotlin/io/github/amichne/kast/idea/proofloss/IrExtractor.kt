package io.github.amichne.kast.idea.proofloss

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.NonEmptyList
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.shared.proofloss.ir.Block
import io.github.amichne.kast.shared.proofloss.ir.ExitKind
import io.github.amichne.kast.shared.proofloss.ir.ExtractionResult
import io.github.amichne.kast.shared.proofloss.ir.FunctionId
import io.github.amichne.kast.shared.proofloss.ir.FunctionIr
import io.github.amichne.kast.shared.proofloss.ir.IrExtractor as SharedIrExtractor
import io.github.amichne.kast.shared.proofloss.ir.PredicateCondition
import io.github.amichne.kast.shared.proofloss.ir.PredicatePolarity
import io.github.amichne.kast.shared.proofloss.ir.SourceOffset
import io.github.amichne.kast.shared.proofloss.ir.SourceSpan
import io.github.amichne.kast.shared.proofloss.ir.Statement
import io.github.amichne.kast.shared.proofloss.ir.TrackedValueId
import io.github.amichne.kast.shared.proofloss.ir.UnsupportedReason
import io.github.amichne.kast.shared.proofloss.ir.ValueExpression
import io.github.amichne.kast.shared.proofloss.model.ArgumentIndex
import io.github.amichne.kast.shared.proofloss.model.ProofModel
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import java.nio.file.Path

internal class IrExtractor(private val model: ProofModel) : SharedIrExtractor<KtNamedFunction> {
    override fun extract(source: KtNamedFunction): ExtractionResult =
        when {
            source.bodyExpression != null && source.bodyBlockExpression == null ->
                rejected(UnsupportedReason.UnsupportedControlFlow(span(requireNotNull(source.bodyExpression))))
            else -> lowerBlock(source.bodyBlockExpression)
        }.toExtractionResult(source)

    private fun lowerBlock(block: KtBlockExpression?): Lowering<Block> =
        block?.statements.orEmpty().map(::lower).toBlock()

    private fun lower(expression: KtExpression): Lowering<Statement> =
        if (expression !is KtLambdaExpression && expression.containsLambda()) {
            rejected(UnsupportedReason.NestedLambda(span(expression)))
        } else when (expression) {
            is KtProperty -> lowerProperty(expression)
            is KtIfExpression -> lowerIf(expression)
            is KtReturnExpression -> Lowering.Emitted(Statement.Exit(ExitKind.RETURN, span(expression)))
            is KtThrowExpression -> Lowering.Emitted(Statement.Exit(ExitKind.THROW, span(expression)))
            is KtLoopExpression -> rejected(UnsupportedReason.Loop(span(expression)))
            is KtCallExpression -> lowerCall(expression)
            is KtLambdaExpression -> rejected(UnsupportedReason.NestedLambda(span(expression)))
            else -> Lowering.Emitted(Statement.NoOp(span(expression)))
        }

    private fun lowerProperty(property: KtProperty): Lowering<Statement> = valueId(property).let { target ->
        when {
            property.isVar -> rejected(UnsupportedReason.MutableTrackedValue(span(property), target))
            property.initializer == null -> Lowering.Emitted(Statement.NoOp(span(property)))
            else -> lowerInitializer(requireNotNull(property.initializer))
                .map { Statement.Let(target, it, span(property)) }
        }
    }

    private fun lowerInitializer(initializer: KtExpression): Lowering<ValueExpression> = when (initializer) {
        is KtNameReferenceExpression -> resolveValue(initializer)
            ?.let { Lowering.Emitted(ValueExpression.Alias(it)) }
            ?: rejected(UnsupportedReason.UnresolvedCall(span(initializer)))
        is KtCallExpression -> materialize(initializer)
        is KtBinaryExpression -> lowerElvis(initializer)
        else -> rejected(UnsupportedReason.UnsupportedControlFlow(span(initializer)))
    }

    private fun lowerElvis(expression: KtBinaryExpression): Lowering<ValueExpression> =
        if (expression.operationToken != KtTokens.ELVIS) {
            rejected(UnsupportedReason.UnsupportedControlFlow(span(expression)))
        } else when (expression.right) {
            is KtReturnExpression, is KtThrowExpression ->
                (expression.left as? KtCallExpression)
                    ?.let(::materialize)
                    ?: rejected(UnsupportedReason.UnsupportedControlFlow(span(expression)))
            else -> rejected(UnsupportedReason.UnprovenMaterializationSuccess(span(expression)))
        }

    private fun materialize(call: KtCallExpression): Lowering<ValueExpression> = call.toCallableKey()
        ?.let { callable ->
            model.predicateForMaterializer(callable)
                ?.let { predicate ->
                    call.valueArguments.singleOrNull()
                        ?.getArgumentExpression()
                        ?.let { it as? KtNameReferenceExpression }
                        ?.let { argument ->
                            resolveValue(argument)
                                ?.let {
                                    Lowering.Emitted(ValueExpression.Materialize(predicate.id, it, callable))
                                }
                                ?: rejected(UnsupportedReason.UnresolvedCall(span(argument)))
                        }
                        ?: rejected(UnsupportedReason.UnsupportedArgumentMapping(span(call)))
                }
                ?: rejected(UnsupportedReason.UnsupportedControlFlow(span(call)))
        }
        ?: rejected(UnsupportedReason.UnresolvedCall(span(call)))

    private fun lowerIf(expression: KtIfExpression): Lowering<Statement> =
        expression.normalizedCondition().flatMap { condition ->
            condition.call.toCallableKey()
                ?.let { callable ->
                    model.predicateForCallable(callable)
                        ?.let { predicate ->
                            condition.call.valueArguments
                                .getOrNull(predicate.subjectArgumentIndex.value)
                                ?.getArgumentExpression()
                                ?.let { it as? KtNameReferenceExpression }
                                ?.let { argument ->
                                    resolveValue(argument)
                                        ?.let { subject ->
                                            listOf(lowerBranch(expression.then), lowerBranch(expression.`else`))
                                                .sequence()
                                                .map { (thenBranch, elseBranch) ->
                                                    Statement.If(
                                                        PredicateCondition(
                                                            predicate.id,
                                                            subject,
                                                            condition.polarity,
                                                            span(condition.call),
                                                        ),
                                                        thenBranch,
                                                        elseBranch,
                                                    )
                                                }
                                        }
                                        ?: rejected(UnsupportedReason.UnresolvedCall(span(argument)))
                                }
                                ?: rejected(UnsupportedReason.UnsupportedArgumentMapping(span(condition.call)))
                        }
                        ?: rejected(UnsupportedReason.UnsupportedControlFlow(span(expression)))
                }
                ?: rejected(UnsupportedReason.UnresolvedCall(span(condition.call)))
        }

    private fun lowerBranch(expression: KtExpression?): Lowering<Block> = when (expression) {
        null -> Lowering.Emitted(Block())
        is KtBlockExpression -> lowerBlock(expression)
        else -> listOf(lower(expression)).toBlock()
    }

    private fun lowerCall(call: KtCallExpression): Lowering<Statement> = call.toCallableKey()
        ?.let { callable ->
            model.boundaryForCallable(callable)
                ?.let { boundary ->
                    boundary.obligations
                        .map { obligation -> call.resolveArgument(obligation.argumentIndex) }
                        .sequence()
                        .map { arguments -> Statement.BoundaryCall(boundary.id, arguments.toMap(), span(call)) }
                }
                ?: Lowering.Ignored
        }
        ?: rejected(UnsupportedReason.UnresolvedCall(span(call)))

    private fun KtCallExpression.resolveArgument(
        index: ArgumentIndex,
    ): Lowering<Pair<ArgumentIndex, TrackedValueId>> = valueArguments
        .getOrNull(index.value)
        ?.takeUnless { it.isNamed() || it.getSpreadElement() != null }
        ?.getArgumentExpression()
        ?.let { expression ->
            (expression as? KtNameReferenceExpression)
                ?.let { reference ->
                    resolveValue(reference)
                        ?.let { Lowering.Emitted(index to it) }
                        ?: rejected(UnsupportedReason.UnresolvedCall(span(reference)))
                }
                ?: rejected(UnsupportedReason.NonDirectBoundaryArgument(span(this)))
        }
        ?: rejected(UnsupportedReason.UnsupportedArgumentMapping(span(this)))

    private fun KtIfExpression.normalizedCondition(): Lowering<NormalizedCondition> = condition
        ?.let { source ->
            when (source) {
                is KtCallExpression -> Lowering.Emitted(NormalizedCondition.Positive(source))
                is KtPrefixExpression -> source
                    .takeIf { it.operationToken == KtTokens.EXCL }
                    ?.baseExpression
                    ?.let { it as? KtCallExpression }
                    ?.let { Lowering.Emitted(NormalizedCondition.Negative(it)) }
                    ?: rejected(UnsupportedReason.UnsupportedControlFlow(span(source)))
                else -> rejected(UnsupportedReason.UnsupportedControlFlow(span(source)))
            }
        }
        ?: rejected(UnsupportedReason.UnsupportedControlFlow(span(this)))

    private fun Lowering<Block>.toExtractionResult(source: KtNamedFunction): ExtractionResult = when (this) {
        is Lowering.Emitted -> ExtractionResult.Supported(
            FunctionIr(functionId(source), source.valueParameters.map(::valueId).toSet(), value),
        )
        is Lowering.Ignored -> ExtractionResult.Supported(
            FunctionIr(functionId(source), source.valueParameters.map(::valueId).toSet(), Block()),
        )
        is Lowering.Rejected -> ExtractionResult.Unsupported(functionId(source), reasons)
    }

    private fun KtExpression.containsLambda(): Boolean =
        PsiTreeUtil.findChildOfType(this, KtLambdaExpression::class.java) != null

    private fun resolveValue(reference: KtNameReferenceExpression): TrackedValueId? =
        (reference.mainReference.resolve() as? KtDeclaration)?.let(::valueId)

    private fun valueId(declaration: KtDeclaration): TrackedValueId =
        TrackedValueId(path(declaration), SourceOffset.valid(declaration.textOffset))

    private fun functionId(function: KtNamedFunction): FunctionId =
        FunctionId(path(function), SourceOffset.valid(function.textOffset))

    private fun span(element: KtElement): SourceSpan = SourceSpan(
        path(element),
        SourceOffset.valid(element.textRange.startOffset),
        SourceOffset.valid(element.textRange.endOffset),
    )

    private fun path(element: KtElement): NormalizedPath =
        NormalizedPath.ofAbsolute(Path.of(element.containingKtFile.virtualFilePath))
}

private sealed interface NormalizedCondition {
    val call: KtCallExpression
    val polarity: PredicatePolarity

    data class Positive(override val call: KtCallExpression) : NormalizedCondition {
        override val polarity: PredicatePolarity = PredicatePolarity.POSITIVE
    }

    data class Negative(override val call: KtCallExpression) : NormalizedCondition {
        override val polarity: PredicatePolarity = PredicatePolarity.NEGATED
    }
}

private sealed interface Lowering<out T> {
    data class Emitted<T>(val value: T) : Lowering<T>
    data object Ignored : Lowering<Nothing>
    data class Rejected(val reasons: NonEmptyList<UnsupportedReason>) : Lowering<Nothing>
}

private inline fun <T, R> Lowering<T>.map(transform: (T) -> R): Lowering<R> = when (this) {
    is Lowering.Emitted -> Lowering.Emitted(transform(value))
    is Lowering.Ignored -> this
    is Lowering.Rejected -> this
}

private inline fun <T, R> Lowering<T>.flatMap(transform: (T) -> Lowering<R>): Lowering<R> = when (this) {
    is Lowering.Emitted -> transform(value)
    is Lowering.Ignored -> this
    is Lowering.Rejected -> this
}

private fun <T> List<Lowering<T>>.sequence(): Lowering<List<T>> =
    flatMap { lowering ->
        when (lowering) {
            is Lowering.Emitted, is Lowering.Ignored -> emptyList()
            is Lowering.Rejected -> lowering.reasons.value
        }
    }.let { reasons ->
        if (reasons.isEmpty()) {
            Lowering.Emitted(
                mapNotNull { lowering ->
                    when (lowering) {
                        is Lowering.Emitted -> lowering.value
                        is Lowering.Ignored, is Lowering.Rejected -> null
                    }
                },
            )
        } else Lowering.Rejected(NonEmptyList(reasons))
    }

private fun List<Lowering<Statement>>.toBlock(): Lowering<Block> = sequence().map(::Block)

private fun <T> rejected(reason: UnsupportedReason): Lowering<T> =
    Lowering.Rejected(NonEmptyList(listOf(reason)))
