@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter

internal data class NativeCallbackSupplierCall(
    val binding: CallbackArgumentBinding,
    val value: KtExpression,
    val selection: NativeCallbackSupplierSelection,
    val lexicalOwner: CompilerGroundedSymbolEvidence,
)

internal enum class NativeCallbackSupplierSelection {
    EXPLICIT,
    DEFAULT,
}

/** Reference enumeration is exhausted before K2 mapping; no compiler or package PSI runs in index callbacks. */
internal class IntellijCallbackSupplierCalls(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(
        function: KtNamedFunction,
        parameter: KtParameter,
        formal: CallbackParameterIdentity,
    ): Refinement<List<NativeCallbackSupplierCall>, CallbackInvocationFlowCause> {
        val references =
            when (val scanned = References(context, summaries).read(function)) {
                is Refinement.Refined -> scanned.value
                is Refinement.Rejected -> return scanned
            }
        val calls = linkedSetOf<KtCallElement>()
        val result = mutableListOf<NativeCallbackSupplierCall>()
        for (reference in references) {
            val candidate =
                when (val selected = select(reference)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            if (candidate is NativeSupplierReference.Call && calls.add(candidate.call)) {
                val call = candidate.call
                when (val mapped = map(call, function, parameter, formal)) {
                    is Refinement.Rejected -> return mapped
                    is Refinement.Refined -> result += mapped.value.calls()
                }
            }
        }
        return Refinement.Refined(result)
    }

    private fun select(reference: PsiReference): Refinement<NativeSupplierReference, CallbackInvocationFlowCause> {
        when (
            context.scope.request.searchConstraints.packageName.admitPackage {
                reference.element.relationPackageEvidence()
            }
        ) {
            IntellijRelationPackageAdmission.ADMITTED -> Unit
            IntellijRelationPackageAdmission.OUTSIDE_SCOPE -> return Refinement.Refined(NativeSupplierReference.Ignored)
            IntellijRelationPackageAdmission.UNSUPPORTED ->
                return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        }
        when (val allowed = context.permit()) {
            is Refinement.Rejected -> return allowed
            is Refinement.Refined -> Unit
        }
        if (PsiTreeUtil.getParentOfType(reference.element, KtImportDirective::class.java, false) != null)
            return Refinement.Refined(NativeSupplierReference.Ignored)
        val call =
            PsiTreeUtil.getParentOfType(reference.element, KtCallElement::class.java, false)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        if (call.calleeExpression?.textRange?.contains(reference.element.textRange) != true)
            return Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        return Refinement.Refined(NativeSupplierReference.Call(call))
    }

    private fun argument(
        call: KtCallElement,
        function: KtNamedFunction,
        parameter: KtParameter,
        formal: CallbackParameterIdentity,
    ): NativeSupplierArgument =
        context.observation.observedAnalyze(call) {
            val resolved = call.resolveCall() ?: return@observedAnalyze NativeSupplierArgument.Unavailable
            val callable =
                resolved.signature.symbol as? KaNamedFunctionSymbol
                    ?: return@observedAnalyze NativeSupplierArgument.Unavailable
            if (callable.psi?.originalElement != function.originalElement)
                return@observedAnalyze NativeSupplierArgument.Different
            val values =
                resolved.valueArgumentMapping
                    .filter { (_, mapped) ->
                        callable.valueParameters.indexOf(mapped.symbol) == formal.position.value
                    }
                    .keys
            when (values.size) {
                0 ->
                    parameter.defaultValue?.let {
                        NativeSupplierArgument.Selected(it, NativeCallbackSupplierSelection.DEFAULT)
                    } ?: NativeSupplierArgument.Unavailable
                1 -> NativeSupplierArgument.Selected(values.single(), NativeCallbackSupplierSelection.EXPLICIT)
                else -> NativeSupplierArgument.Unavailable
            }
        }

    private fun map(
        call: KtCallElement,
        function: KtNamedFunction,
        parameter: KtParameter,
        formal: CallbackParameterIdentity,
    ): Refinement<NativeSupplierMapping, CallbackInvocationFlowCause> {
        val argument = argument(call, function, parameter, formal)
        when (argument) {
            NativeSupplierArgument.Different -> return Refinement.Refined(NativeSupplierMapping.Different)
            NativeSupplierArgument.Unavailable ->
                return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            is NativeSupplierArgument.Selected -> Unit
        }
        val declaration =
            when (val found = call.nearestDeclaration()) {
                is ContainingDeclaration.Deferred -> found.enclosingDeclaration()
                is ContainingDeclaration.Found,
                ContainingDeclaration.Unsupported -> found
            }
                as? ContainingDeclaration.Found
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val owner =
            context.receiverDeclaration(declaration.declaration)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val enclosing =
            context.endpoint(owner) ?: return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        val body =
            context.owner(call)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val range =
            context.range(call.valueInvocationExpression())
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val invocation =
            when (val admitted = ValueInvocation.fromCompiler(enclosing, range, formal.callable)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        val binding =
            when (
                val admitted = CallbackArgumentBinding.fromCompiler(invocation, body, formal.position, formal.parameter)
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        return Refinement.Refined(
            NativeSupplierMapping.Mapped(NativeCallbackSupplierCall(binding, argument.value, argument.selection, owner))
        )
    }
}

private sealed interface NativeSupplierArgument {
    data object Different : NativeSupplierArgument

    data object Unavailable : NativeSupplierArgument

    data class Selected(val value: KtExpression, val selection: NativeCallbackSupplierSelection) :
        NativeSupplierArgument
}

private sealed interface NativeSupplierMapping {
    data object Different : NativeSupplierMapping

    data class Mapped(val value: NativeCallbackSupplierCall) : NativeSupplierMapping
}

private sealed interface NativeSupplierReference {
    data object Ignored : NativeSupplierReference

    data class Call(val call: KtCallElement) : NativeSupplierReference
}

private class References(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    private val references = mutableListOf<PsiReference>()
    private var completion: Refinement<Unit, CallbackInvocationFlowCause> = Refinement.Refined(Unit)

    fun read(function: KtNamedFunction): Refinement<List<PsiReference>, CallbackInvocationFlowCause> {
        val admission =
            NativeRelationScopeAdmission(context.observation) {
                when (val admitted = context.admitNativeScope()) {
                    is Refinement.Refined -> IntellijRelationProviderEnumerationAdmission.READY
                    is Refinement.Rejected -> {
                        completion = admitted
                        IntellijRelationProviderEnumerationAdmission.HALTED
                    }
                }
            }
        val exhausted =
            context.observation.forEachReference(function, context.scope.nativeScope, admission, false, ::visit)
        when (val completed = completion) {
            is Refinement.Rejected -> return completed
            is Refinement.Refined -> Unit
        }
        if (!exhausted) return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return Refinement.Refined(references)
    }

    private fun visit(reference: PsiReference): Boolean {
        val site =
            when (val admitted = context.providerSite { reference.element.containingFile?.virtualFile }) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> {
                    completion = admitted
                    return false
                }
            }
        return when (site) {
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
            RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED -> true
            RelationProviderScopeAdmission.UNAVAILABLE -> {
                completion = Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
                false
            }
            RelationProviderScopeAdmission.ADMITTED -> retain(reference)
        }
    }

    private fun retain(reference: PsiReference): Boolean {
        completion =
            when (val allowed = context.permit()) {
                is Refinement.Rejected -> allowed
                is Refinement.Refined -> summaries.retention.admit(REFERENCE_RETENTION_BYTES)
            }
        return when (completion) {
            is Refinement.Rejected -> false
            is Refinement.Refined -> {
                references += reference
                true
            }
        }
    }

    private companion object {
        const val REFERENCE_RETENTION_BYTES = 256L
    }
}

private fun NativeSupplierMapping.calls(): List<NativeCallbackSupplierCall> =
    when (this) {
        NativeSupplierMapping.Different -> emptyList()
        is NativeSupplierMapping.Mapped -> listOf(value)
    }
