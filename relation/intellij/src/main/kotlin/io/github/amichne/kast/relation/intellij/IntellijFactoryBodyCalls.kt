@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackFactoryBodyCall
import io.github.amichne.kast.relation.contract.CallbackFactoryBodyCalls
import io.github.amichne.kast.relation.contract.CallbackFactoryCapture
import io.github.amichne.kast.relation.contract.CallbackFactoryCaptureContent
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableDisposition
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtOperationExpression
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Inventories execution at the returned body's source owner, never at its supplying caller. */
internal class IntellijFactoryBodyCalls(private val context: IntellijCallbackFlowContext) {
    fun read(
        function: KtNamedFunction,
        origin: ImmutableCallbackValueOrigin,
        captures: List<CallbackFactoryCapture>,
    ): Refinement<CallbackFactoryBodyCalls, CallbackInvocationFlowCause> {
        if (origin !is ImmutableCallbackValueOrigin.Anonymous)
            return Refinement.Refined(CallbackFactoryBodyCalls.NotApplicable)
        val body = origin.body
        val nativeBody =
            when (val found = findBody(function, body)) {
                is Refinement.Refined -> found.value
                is Refinement.Rejected -> return found
            }
        val pending = ArrayDeque<PsiElement>()
        pending.addAll(nativeBody.children)
        val calls = mutableListOf<CallbackFactoryBodyCall>()
        while (pending.isNotEmpty()) {
            when (val permit = context.permit()) {
                is Refinement.Rejected -> return permit
                is Refinement.Refined -> Unit
            }
            val element = pending.removeFirst()
            if (element.hasUnmodeledExecution())
                return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            when (val read = call(element, body, captures)) {
                is Refinement.Rejected -> return read
                is Refinement.Refined -> calls += read.value
            }
            pending.addAll(element.children)
        }
        return when (
            val admitted =
                CallbackFactoryBodyCalls.Exhaustive.fromCompiler(body, calls, CallbackInvocationScan.EXHAUSTIVE)
        ) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun findBody(
        function: KtNamedFunction,
        body: RelationCallableBody.Anonymous,
    ): Refinement<KtFunction, CallbackInvocationFlowCause> {
        val pending = ArrayDeque<PsiElement>()
        pending.add(function)
        while (pending.isNotEmpty()) {
            when (val permit = context.permit()) {
                is Refinement.Rejected -> return permit
                is Refinement.Refined -> Unit
            }
            val element = pending.removeFirst()
            val range = element.textRange ?: return rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
            if (range.endOffset <= body.range.startInclusive || range.startOffset >= body.range.endExclusive) continue
            if (element is KtFunction && context.range(element) == body.range) return admitBody(element, body)
            pending.addAll(element.children)
        }
        return rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    }

    private fun admitBody(
        element: KtFunction,
        body: RelationCallableBody.Anonymous,
    ): Refinement<KtFunction, CallbackInvocationFlowCause> =
        if (context.anonymous(element) == body) Refinement.Refined(element)
        else rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)

    private fun call(
        element: PsiElement,
        body: RelationCallableBody.Anonymous,
        captures: List<CallbackFactoryCapture>,
    ): Refinement<List<CallbackFactoryBodyCall>, CallbackInvocationFlowCause> {
        val call = element as? KtCallExpression ?: return Refinement.Refined(emptyList())
        if (context.owner(call) != body) return rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        val occurrence =
            context.occurrence(call.valueInvocationExpression())
                ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return analyze(call) {
            val symbol =
                call.resolveCall()?.signature?.symbol
                    ?: return@analyze rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            val proof =
                when {
                    symbol is KaNamedFunctionSymbol && confirmsBuiltinFunctionInvoke(symbol) ->
                        captured(occurrence, captures)
                    symbol.psi == null ->
                        when (val boundary = sourceLessCallable(symbol)) {
                            is IntellijK2ResolvedDeclaration.SourceLess -> sourceLess(occurrence, boundary.callable)
                            else -> rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
                        }
                    else -> named(occurrence, symbol.psi)
                }
            when (proof) {
                is Refinement.Refined -> Refinement.Refined(listOf(proof.value))
                is Refinement.Rejected -> proof
            }
        }
    }

    private fun captured(
        occurrence: RelationOccurrence,
        captures: List<CallbackFactoryCapture>,
    ): Refinement<CallbackFactoryBodyCall, CallbackInvocationFlowCause> {
        val matches = captures.flatMap { capture ->
            val content = capture.content as? CallbackFactoryCaptureContent.Callable
            content?.invocations.orEmpty().filter { it.occurrence == occurrence }.map { capture to it }
        }
        val (capture, invocation) =
            matches.singleOrNull() ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        val binding = capture.binding
        return when (
            val formal =
                CallbackParameterIdentity.fromCompiler(binding.invocation.callable, binding.position, binding.parameter)
        ) {
            is Refinement.Refined -> Refinement.Refined(CallbackFactoryBodyCall.Captured(invocation, formal.value))
            is Refinement.Rejected -> rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun named(
        occurrence: RelationOccurrence,
        declaration: PsiElement?,
    ): Refinement<CallbackFactoryBodyCall, CallbackInvocationFlowCause> {
        val function =
            declaration as? KtNamedFunction ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        if (function.containingFile?.virtualFile?.let(context.scope.nativeScope::contains) != true)
            return rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        return when (val target = context.target(function)) {
            is Refinement.Refined -> Refinement.Refined(CallbackFactoryBodyCall.Named(occurrence, target.value))
            is Refinement.Rejected -> target
        }
    }

    private fun sourceLess(
        occurrence: RelationOccurrence,
        callable: SourceLessCallable,
    ): Refinement<CallbackFactoryBodyCall, CallbackInvocationFlowCause> {
        val libraries =
            (context.scope.request.searchScope as? SymbolSearchScope.Workspace)?.libraries
                ?: SymbolLibraryPolicy.EXCLUDE
        val disposition =
            when (callable.moduleKind) {
                SourceLessCallableModuleKind.BUILTINS -> SourceLessCallableDisposition.BUILTIN_BOUNDARY
                SourceLessCallableModuleKind.LIBRARY ->
                    when (libraries) {
                        SymbolLibraryPolicy.EXCLUDE -> SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED
                        SymbolLibraryPolicy.INCLUDE -> return rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
                    }
            }
        return Refinement.Refined(CallbackFactoryBodyCall.Boundary(occurrence, callable, disposition))
    }

    private fun rejected(cause: CallbackInvocationFlowCause) = Refinement.Rejected(cause)
}

private fun PsiElement.hasUnmodeledExecution(): Boolean =
    this is KtForExpression ||
        this is KtDestructuringDeclaration ||
        this is KtPropertyDelegate ||
        this is KtClassOrObject ||
        this is KtOperationExpression ||
        this is KtArrayAccessExpression ||
        this is KtStringTemplateEntryWithExpression ||
        (this is KtWhenExpression && (subjectExpression != null || subjectVariable != null))
