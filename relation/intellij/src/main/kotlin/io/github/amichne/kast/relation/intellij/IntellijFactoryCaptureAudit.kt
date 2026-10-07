package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtValueArgumentName

private enum class NativeCaptureReference {
    ADMITTED,
    MUTABLE,
    UNSUPPORTED,
    UNRESOLVED,
}

private enum class NativeCaptureAlias {
    EXACT_FORMAL,
    OTHER,
}

/** Free variable acceptance is separate from the exact callable-capture invocation inventory. */
internal class IntellijFactoryCaptureAudit(private val context: IntellijCallbackFlowContext) {
    fun read(function: KtNamedFunction, value: ImmutableCallbackValue): Refinement<Unit, CallbackInvocationFlowCause> {
        val range =
            when (val origin = value.origin) {
                is ImmutableCallbackValueOrigin.Anonymous -> origin.body.range
                is ImmutableCallbackValueOrigin.Named -> origin.occurrence.range
                is ImmutableCallbackValueOrigin.Returned -> return Refinement.Refined(Unit)
            }
        val pending = ArrayDeque<PsiElement>()
        pending.add(function)
        while (pending.isNotEmpty()) {
            when (val permit = context.permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return permit
            }
            val element = pending.removeFirst()
            if (
                element.textRange.endOffset < range.startInclusive || element.textRange.startOffset > range.endExclusive
            )
                continue
            if (range.contains(element))
                when (val admitted = element(element, function, range)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return admitted
                }
            element.children.forEach(pending::addLast)
        }
        return Refinement.Refined(Unit)
    }

    private fun element(
        element: PsiElement,
        function: KtNamedFunction,
        range: ExactDeclarationTextRange,
    ): Refinement<Unit, CallbackInvocationFlowCause> {
        if (element is KtThisExpression) return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        if (element !is KtNameReferenceExpression || element.parent is KtValueArgumentName)
            return Refinement.Refined(Unit)
        val reference =
            element.references
                .filterIsInstance<KtReference>()
                .filterNot { it is KtInvokeFunctionReference }
                .singleOrNull() ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        when (val alias = alias(reference, function, range)) {
            is Refinement.Rejected -> return alias
            is Refinement.Refined -> if (alias.value == NativeCaptureAlias.EXACT_FORMAL) return Refinement.Refined(Unit)
        }
        return when (reference(reference, function, range)) {
            NativeCaptureReference.ADMITTED -> Refinement.Refined(Unit)
            NativeCaptureReference.MUTABLE -> rejected(CallbackInvocationFlowCause.STORED_CALLBACK)
            NativeCaptureReference.UNSUPPORTED -> rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            NativeCaptureReference.UNRESOLVED -> rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        }
    }

    private fun alias(
        reference: KtReference,
        function: KtNamedFunction,
        range: ExactDeclarationTextRange,
    ): Refinement<NativeCaptureAlias, CallbackInvocationFlowCause> {
        val local =
            when (val resolved = resolveLocalValueReference(reference)) {
                is NativeLocalValueReferenceResolution.Resolved -> resolved.declaration as? KtProperty
                NativeLocalValueReferenceResolution.Unresolved -> null
            }
        if (local == null || !local.immutableLocal() || range.contains(local))
            return Refinement.Refined(NativeCaptureAlias.OTHER)
        val initializer = local.initializer ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        val factory =
            when (val found = context.target(function)) {
                is Refinement.Refined -> found.value
                is Refinement.Rejected -> return found
            }
        return when (val upstream = IntellijCallbackSupplierFormal(context).read(initializer, factory.evidence)) {
            is Refinement.Rejected -> upstream
            is Refinement.Refined -> {
                val formal = upstream.value
                val admitted =
                    formal is NativeCallbackSupplierFormal.Found &&
                        formal.function.originalElement == function.originalElement
                Refinement.Refined(if (admitted) NativeCaptureAlias.EXACT_FORMAL else NativeCaptureAlias.OTHER)
            }
        }
    }

    private fun reference(
        reference: KtReference,
        function: KtNamedFunction,
        range: ExactDeclarationTextRange,
    ): NativeCaptureReference =
        analyze(reference.element) {
            when (val symbol = reference.resolveToSymbol()) {
                is KaNamedFunctionSymbol -> named(symbol.psi as? KtNamedFunction, function, range)
                is KaClassLikeSymbol ->
                    if (symbol.psi is KtObjectDeclaration) NativeCaptureReference.UNSUPPORTED
                    else NativeCaptureReference.ADMITTED
                is KaVariableSymbol -> variable(symbol.psi, function, range)
                else -> NativeCaptureReference.UNRESOLVED
            }
        }

    private fun named(
        declaration: KtNamedFunction?,
        function: KtNamedFunction,
        range: ExactDeclarationTextRange,
    ): NativeCaptureReference =
        if (declaration != null && declaration.outsideLocal(function, range)) NativeCaptureReference.UNSUPPORTED
        else NativeCaptureReference.ADMITTED

    private fun variable(
        declaration: PsiElement?,
        function: KtNamedFunction,
        range: ExactDeclarationTextRange,
    ): NativeCaptureReference =
        when {
            declaration != null && function.valueParameters.any { it.originalElement == declaration.originalElement } ->
                NativeCaptureReference.ADMITTED
            declaration != null &&
                declaration.containingFile == function.containingFile &&
                range.contains(declaration) -> NativeCaptureReference.ADMITTED
            declaration is KtProperty && declaration.isVar -> NativeCaptureReference.MUTABLE
            else -> NativeCaptureReference.UNSUPPORTED
        }

    private fun rejected(cause: CallbackInvocationFlowCause) = Refinement.Rejected(cause)
}

private fun ExactDeclarationTextRange.contains(element: PsiElement): Boolean =
    startInclusive <= element.textRange.startOffset && element.textRange.endOffset <= endExclusive

private fun KtProperty.immutableLocal(): Boolean = isLocal && !isVar && !hasDelegate()

private fun KtNamedFunction.outsideLocal(function: KtNamedFunction, range: ExactDeclarationTextRange): Boolean =
    isLocal && containingFile == function.containingFile && !range.contains(this)
