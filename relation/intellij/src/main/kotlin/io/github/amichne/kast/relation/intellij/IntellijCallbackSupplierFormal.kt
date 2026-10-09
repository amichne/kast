@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty

internal sealed interface NativeCallbackSupplierFormal {
    data object NotFormal : NativeCallbackSupplierFormal

    data class Found(
        val formal: CallbackParameterIdentity,
        val function: KtNamedFunction,
        val parameter: KtParameter,
        val transfers: List<ValueTransfer>,
    ) : NativeCallbackSupplierFormal
}

/** Resolves upstream formal identity through immutable local aliases without merging supplier call contexts. */
internal class IntellijCallbackSupplierFormal(private val context: IntellijCallbackFlowContext) {
    private data class Alias(val property: KtProperty, val read: KtNameReferenceExpression)

    fun read(
        expression: KtExpression,
        lexicalOwner: CompilerGroundedSymbolEvidence,
    ): Refinement<NativeCallbackSupplierFormal, CallbackInvocationFlowCause> {
        var current = expression
        val aliases = mutableListOf<Alias>()
        val visited = mutableSetOf<PsiElement>()
        while (true) {
            when (val permitted = context.permit()) {
                is Refinement.Rejected -> return permitted
                is Refinement.Refined -> Unit
            }
            if (!visited.add(current)) return rejected(CallbackInvocationFlowCause.CALLBACK_CYCLE)
            if (current is KtParenthesizedExpression) {
                current =
                    current.expression ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
                continue
            }
            val step =
                when (val resolved = step(current, lexicalOwner)) {
                    is Refinement.Refined -> resolved.value
                    is Refinement.Rejected -> return resolved
                }
            when (step) {
                FormalStep.NotFormal -> return Refinement.Refined(NativeCallbackSupplierFormal.NotFormal)
                is FormalStep.Parameter -> return formal(step.parameter, step.read, aliases, lexicalOwner)
                is FormalStep.Local -> {
                    aliases += step.alias
                    current = step.initializer
                }
            }
        }
    }

    private sealed interface FormalStep {
        data object NotFormal : FormalStep

        data class Parameter(val parameter: KtParameter, val read: KtNameReferenceExpression) : FormalStep

        data class Local(val alias: Alias, val initializer: KtExpression) : FormalStep
    }

    private fun step(
        current: KtExpression,
        owner: CompilerGroundedSymbolEvidence,
    ): Refinement<FormalStep, CallbackInvocationFlowCause> {
        val expression = current as? KtNameReferenceExpression ?: return Refinement.Refined(FormalStep.NotFormal)
        val declaration =
            when (val resolved = declaration(expression)) {
                is Refinement.Refined -> resolved.value
                is Refinement.Rejected -> return resolved
            }
        return when (declaration) {
            is KtParameter -> Refinement.Refined(FormalStep.Parameter(declaration, expression))
            is KtProperty ->
                when (val admitted = alias(declaration, expression, owner)) {
                    is Refinement.Refined ->
                        Refinement.Refined(FormalStep.Local(Alias(declaration, expression), admitted.value))
                    is Refinement.Rejected -> admitted
                }
            else -> Refinement.Refined(FormalStep.NotFormal)
        }
    }

    private fun formal(
        parameter: KtParameter,
        source: KtNameReferenceExpression,
        aliases: List<Alias>,
        lexicalOwner: CompilerGroundedSymbolEvidence,
    ): Refinement<NativeCallbackSupplierFormal, CallbackInvocationFlowCause> {
        val function =
            PsiTreeUtil.getParentOfType(parameter, KtNamedFunction::class.java, false)
                ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        if (
            function.name == null ||
                !context.observation.observedAnalyze(parameter) { parameter.symbol.returnType is KaFunctionType }
        )
            return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        val endpoint =
            when (val admitted = context.target(function)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        if (endpoint.compilerIdentity != lexicalOwner.compilerIdentity)
            return rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        val position =
            when (val admitted = ValueArgumentPosition.parse(function.valueParameters.indexOf(parameter))) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
            }
        val occurrence =
            context.occurrence(parameter) ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        val identity =
            when (val admitted = CallbackParameterIdentity.fromCompiler(endpoint, position, occurrence)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
            }
        return when (val read = transfers(source, aliases, endpoint)) {
            is Refinement.Refined ->
                Refinement.Refined(NativeCallbackSupplierFormal.Found(identity, function, parameter, read.value))
            is Refinement.Rejected -> read
        }
    }

    private fun transfers(
        source: KtNameReferenceExpression,
        aliases: List<Alias>,
        endpoint: io.github.amichne.kast.relation.contract.RelationEndpoint.Resolved,
    ): Refinement<List<ValueTransfer>, CallbackInvocationFlowCause> {
        val transfers = mutableListOf<ValueTransfer>()
        var previous =
            context.site(source, endpoint, ValueRole.ExpressionResult)
                ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        for (alias in aliases.asReversed()) {
            val binding =
                context.site(alias.property, endpoint, ValueRole.LocalBinding)
                    ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
            val read =
                context.site(alias.read, endpoint, ValueRole.LocalRead)
                    ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
            for ((destination, kind) in
                listOf(binding to ValueTransferKind.LOCAL_BINDING, read to ValueTransferKind.LOCAL_READ)) {
                when (val admitted = ValueTransfer.fromCompiler(previous, destination, kind)) {
                    is Refinement.Refined -> transfers += admitted.value
                    is Refinement.Rejected ->
                        return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
                }
                previous = destination
            }
        }
        return Refinement.Refined(transfers)
    }

    private fun declaration(
        expression: KtNameReferenceExpression
    ): Refinement<PsiElement, CallbackInvocationFlowCause> {
        val reference =
            expression.references
                .filterIsInstance<KtReference>()
                .filterNot { it is KtInvokeFunctionReference }
                .singleOrNull() ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        return when (val resolved = resolveLocalValueReference(reference, context.observation)) {
            is NativeLocalValueReferenceResolution.Resolved -> Refinement.Refined(resolved.declaration)
            NativeLocalValueReferenceResolution.Unresolved ->
                rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        }
    }

    private fun alias(
        declaration: KtProperty,
        expression: KtNameReferenceExpression,
        lexicalOwner: CompilerGroundedSymbolEvidence,
    ): Refinement<KtExpression, CallbackInvocationFlowCause> {
        if (!declaration.isLocal || declaration.isVar || declaration.hasDelegate())
            return rejected(CallbackInvocationFlowCause.STORED_CALLBACK)
        if (
            context.owner(declaration)?.compilerIdentity != lexicalOwner.compilerIdentity ||
                context.owner(expression)?.compilerIdentity != lexicalOwner.compilerIdentity
        )
            return rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        return declaration.initializer?.let { Refinement.Refined(it) }
            ?: rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
    }

    private fun rejected(cause: CallbackInvocationFlowCause) = Refinement.Rejected(cause)
}
