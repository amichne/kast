@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/** The reviewed fallback admits one actual call. Generic source and library scope stay unchanged. */
internal class NativeValueReviewedCall(
    private val caller: ValueExecutionOwner,
    scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
) {
    private val artifact = NativeValueReviewedArtifact(caller, scope, projection, permit)

    fun bind(
        expression: KtExpression,
        qualified: KtQualifiedExpression,
        model: ReviewedValueModel,
    ): Refinement<NativeValueReviewedReceiver, NativeValueModelFailure> {
        if (qualified.receiverExpression !== expression) return artifactRejected(NativeValueArtifactFailure.MISSING)
        val call =
            qualified.selectorExpression as? KtCallElement
                ?: return artifactRejected(NativeValueArtifactFailure.MISSING)
        when (val admitted = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return Refinement.Rejected(NativeValueModelFailure.Limited(admitted.failure))
        }
        val target =
            when (
                val actual =
                    projection.observation.observedAnalyze(call) { receiverTarget(call, expression, qualified, model) }
            ) {
                is Refinement.Refined -> actual.value
                is Refinement.Rejected -> return actual
            }
        return when (val actual = artifact.bind(target.declaration, qualified, model)) {
            is Refinement.Refined -> Refinement.Refined(NativeValueReviewedReceiver(actual.value, target.navigation))
            is Refinement.Rejected -> actual
        }
    }

    fun bindArgument(
        expression: KtExpression,
        argument: KtValueArgument,
    ): Refinement<NativeValueReviewedArgument, NativeValueModelFailure> {
        val call =
            nativeReviewedArgumentCall(expression, argument)
                ?: return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
        when (val admitted = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return Refinement.Rejected(NativeValueModelFailure.Limited(admitted.failure))
        }
        val target =
            when (val actual = projection.observation.observedAnalyze(call) { argumentTarget(call, expression) }) {
                is Refinement.Refined -> actual.value
                is Refinement.Rejected -> return actual
            }
        return when (
            val actual =
                artifact.bind(
                    target.declaration,
                    call.valueInvocationExpression(),
                    ReviewedValueModel.SingleElementListOf,
                )
        ) {
            is Refinement.Refined -> Refinement.Refined(NativeValueReviewedArgument(actual.value, target.position))
            is Refinement.Rejected -> actual
        }
    }

    private fun KaSession.receiverTarget(
        call: KtCallElement,
        expression: KtExpression,
        qualified: KtQualifiedExpression,
        model: ReviewedValueModel,
    ): Refinement<NativeValueReviewedReceiverTarget, NativeValueModelFailure> {
        val resolved = call.resolveCall() ?: return artifactRejected(NativeValueArtifactFailure.MISSING)
        val symbol =
            resolved.signature.symbol as? KaNamedFunctionSymbol
                ?: return artifactRejected(NativeValueArtifactFailure.NOT_LIBRARY)
        if (
            nativeExplicitReceiverKind(expression, resolved.extensionReceiver, resolved.dispatchReceiver) !=
                ValueReceiverKind.EXTENSION
        )
            return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
        val navigation =
            when (
                val actual =
                    nativeReviewedNavigation(
                        model = model,
                        enclosing = caller.enclosing,
                        expression = expression,
                        qualified = qualified,
                        receiver = resolved.extensionReceiver,
                    )
            ) {
                is Refinement.Refined -> actual.value
                is Refinement.Rejected -> return actual
            }
        return when (val actual = declaration(symbol, model)) {
            is Refinement.Refined -> Refinement.Refined(NativeValueReviewedReceiverTarget(actual.value, navigation))
            is Refinement.Rejected -> actual
        }
    }

    private fun KaSession.argumentTarget(
        call: KtCallElement,
        expression: KtExpression,
    ): Refinement<NativeValueReviewedArgumentTarget, NativeValueModelFailure> {
        val resolved = call.resolveCall() ?: return artifactRejected(NativeValueArtifactFailure.MISSING)
        val symbol =
            resolved.signature.symbol as? KaNamedFunctionSymbol
                ?: return artifactRejected(NativeValueArtifactFailure.NOT_LIBRARY)
        val parameter =
            symbol.valueParameters.singleOrNull()
                ?: return artifactRejected(NativeValueArtifactFailure.UNREVIEWED_CALLABLE)
        if (parameter.isVararg || resolved.extensionReceiver != null || resolved.dispatchReceiver != null)
            return artifactRejected(NativeValueArtifactFailure.UNREVIEWED_CALLABLE)
        if (resolved.valueArgumentMapping[expression]?.symbol != parameter)
            return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
        val position =
            when (val actual = ValueArgumentPosition.parse(symbol.valueParameters.indexOf(parameter))) {
                is Refinement.Refined -> actual.value
                is Refinement.Rejected -> return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
            }
        return when (val actual = declaration(symbol, ReviewedValueModel.SingleElementListOf)) {
            is Refinement.Refined -> Refinement.Refined(NativeValueReviewedArgumentTarget(actual.value, position))
            is Refinement.Rejected -> actual
        }
    }

    private fun KaSession.declaration(
        symbol: KaNamedFunctionSymbol,
        model: ReviewedValueModel,
    ): Refinement<NativeValueReviewedTarget, NativeValueModelFailure> {
        if (symbol.origin != KaSymbolOrigin.LIBRARY) return artifactRejected(NativeValueArtifactFailure.NOT_LIBRARY)
        val canonical = symbol.compilerProjection(this)
        if (
            canonical !is IntellijCompilerProjectionResult.Projected || canonical.projection.signature != model.callable
        )
            return artifactRejected(NativeValueArtifactFailure.UNREVIEWED_CALLABLE)
        val declaration = symbol.psi as? PsiNamedElement ?: return artifactRejected(NativeValueArtifactFailure.MISSING)
        val module =
            when (val containing = symbol.containingModule) {
                is KaLibraryModule -> containing
                is KaLibrarySourceModule -> containing.binaryLibrary
                else -> return artifactRejected(NativeValueArtifactFailure.NOT_LIBRARY)
            }
        return artifact.target(module, declaration)
    }
}

internal data class NativeValueReviewedArgument(
    val binding: ReviewedLibraryBinding,
    val position: ValueArgumentPosition,
)

private data class NativeValueReviewedReceiverTarget(
    val declaration: NativeValueReviewedTarget,
    val navigation: NativeValueReviewedNavigation,
)

private data class NativeValueReviewedArgumentTarget(
    val declaration: NativeValueReviewedTarget,
    val position: ValueArgumentPosition,
)

internal fun nativeReviewedArgumentCall(expression: KtExpression, argument: KtValueArgument): KtCallElement? {
    if (argument.getArgumentExpression() !== expression || argument.getSpreadElement() != null) return null
    val call =
        when (val parent = argument.parent) {
            is KtCallElement -> parent
            is KtValueArgumentList -> parent.parent as? KtCallElement
            else -> null
        } ?: return null
    val name = ReviewedValueModel.SingleElementListOf.callable.qualifiedIdentity.value.substringAfterLast('.')
    return call.takeIf { it.calleeExpression?.text == name && it.valueArguments.singleOrNull() === argument }
}
