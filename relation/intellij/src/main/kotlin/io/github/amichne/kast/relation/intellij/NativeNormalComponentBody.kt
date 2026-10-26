@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThisExpression

/** Bounded source interpretation after K2 selects a final member and the exact constructor storage receiver. */
internal class NativeNormalComponentBody(
    private val enclosing: RelationEndpoint,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
) {
    fun readLoop(function: KtNamedFunction): Refinement<Unit, ValueFlowUnsupportedCause> {
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        if (function.hasBlockBody()) return unclassifiedBody()
        return when (val body = function.bodyExpression) {
            is KtThisExpression ->
                when (val receiver = nativeThisReceiverKind(function, body, projection.observation)) {
                    is Refinement.Rejected -> receiver
                    is Refinement.Refined ->
                        if (receiver.value == ValueReceiverKind.DISPATCH) Refinement.Refined(Unit)
                        else unclassifiedBody()
                }
            is KtNameReferenceExpression ->
                field(body, NativeComponentTarget.Source(function, ValueReceiverKind.DISPATCH))
            is KtConstantExpression -> literal(body)
            else -> unclassifiedBody()
        }
    }

    fun read(
        entry: KtDestructuringDeclarationEntry,
        target: NativeComponentTarget.Source,
    ): Refinement<Unit, ValueFlowUnsupportedCause> {
        val function = target.declaration
        if (target.receiver != ValueReceiverKind.DISPATCH || function.hasBlockBody()) return unclassifiedBody()
        when (
            val callable =
                nativeValueCallableEndpoint(
                    declaration = function,
                    enclosing = enclosing,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return callable
        }
        val selected =
            projection.observation.observedAnalyze(entry) {
                val symbol = function.symbol
                val call = entry.resolveCall()
                val receiver = call?.dispatchReceiver?.type as? KaClassType
                val exactBody = call?.signature?.symbol == symbol && symbol.modality == KaSymbolModality.FINAL
                exactBody && symbol.receiverParameter == null && receiver?.isMarkedNullable == false
            }
        if (!selected) return unclassifiedBody()
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        return when (val body = function.bodyExpression) {
            is KtNameReferenceExpression -> field(body, target)
            is KtConstantExpression -> literal(body)
            is KtStringTemplateExpression -> stringLiteral(body)
            else -> unclassifiedBody()
        }
    }

    private fun field(
        expression: KtNameReferenceExpression,
        target: NativeComponentTarget.Source,
    ): Refinement<Unit, ValueFlowUnsupportedCause> {
        val parameter = projection.observation.observedAnalyze(expression) { constructorField(expression, target) }
        return when (parameter) {
            null -> unclassifiedBody()
            else -> stored(parameter)
        }
    }

    fun stored(parameter: KtParameter): Refinement<Unit, ValueFlowUnsupportedCause> =
        when (
            val field =
                nativeValueField(
                    declaration = parameter,
                    enclosing = enclosing,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> Refinement.Refined(Unit)
            is Refinement.Rejected -> field
        }

    private fun KaSession.constructorField(
        expression: KtNameReferenceExpression,
        target: NativeComponentTarget.Source,
    ): KtParameter? {
        val call = expression.resolveCall()
        val property = call?.signature?.symbol as? KaKotlinPropertySymbol
        if (call == null || property == null) return null
        val parameter = property.psi as? KtParameter
        val owner = parameter?.ownerFunction?.parent as? KtClass
        if (parameter == null || owner == null || parameter !in owner.primaryConstructorParameters) return null
        val receiver = call.dispatchReceiver as? KaImplicitReceiverValue
        val functionOwner = target.declaration.symbol.containingDeclaration
        if (property.containingDeclaration != functionOwner || receiver?.symbol != functionOwner) return null
        if (call.extensionReceiver != null || property.returnType is KaErrorType) return null
        return when ((parameter.symbol as? KaValueParameterSymbol)?.generatedPrimaryConstructorProperty) {
            property -> parameter
            else -> null
        }
    }

    private fun literal(expression: KtExpression): Refinement<Unit, ValueFlowUnsupportedCause> =
        projection.observation.observedAnalyze(expression) {
            when (expression.expressionType) {
                null,
                is KaErrorType -> unclassifiedBody()
                else -> Refinement.Refined(Unit)
            }
        }

    private fun stringLiteral(expression: KtStringTemplateExpression): Refinement<Unit, ValueFlowUnsupportedCause> {
        for (entry in expression.entries) {
            when (val allowed = permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return allowed
            }
            if (entry !is KtLiteralStringTemplateEntry && entry !is KtEscapeStringTemplateEntry)
                return unclassifiedBody()
        }
        return literal(expression)
    }
}

private fun unclassifiedBody(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
