@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
    org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class,
)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableFailure
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleName
import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaBuiltinsModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Only a compiler-resolved builtin function invocation is a symbolic function-value parameter callee. */
internal fun KaSession.resolvedParameterInvocation(
    reference: KtReference,
    resolved: KaSymbol,
): IntellijK2ResolvedDeclaration? {
    val call = reference.element.parent as? KtCallExpression ?: return null
    val invoked = call.resolveCall()?.signature?.symbol as? KaNamedFunctionSymbol ?: return null
    if (!confirmsBuiltinFunctionInvoke(invoked))
        return if (resolved is KaValueParameterSymbol) IntellijK2ResolvedDeclaration.InvokeReceiver else null
    val parameterSymbol =
        resolvedInvocationParameter(call, resolved) ?: return resolvedValueReceiver(call, resolved, invoked)
    val owner =
        parameterSymbol.containingDeclaration as? KaFunctionSymbol
            ?: return unsupported(IntellijResolvedCallableFailure.PARAMETER_OWNER_UNAVAILABLE)
    val callable =
        owner.psi as? KtNamedFunction ?: return unsupported(IntellijResolvedCallableFailure.PARAMETER_OWNER_UNAVAILABLE)
    if (callable.name == null) return unsupported(IntellijResolvedCallableFailure.PARAMETER_OWNER_UNAVAILABLE)
    val parameter =
        parameterSymbol.psi as? KtParameter
            ?: return unsupported(IntellijResolvedCallableFailure.PARAMETER_OWNER_UNAVAILABLE)
    val position =
        when (val parsed = ValueArgumentPosition.parse(owner.valueParameters.indexOf(parameterSymbol))) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return unsupported(IntellijResolvedCallableFailure.PARAMETER_POSITION_UNAVAILABLE)
        }
    return IntellijK2ResolvedDeclaration.ParameterInvocation(callable, parameter, position, call)
}

private fun KaSession.resolvedValueReceiver(
    call: KtCallExpression,
    resolved: KaSymbol,
    invoked: KaNamedFunctionSymbol,
): IntellijK2ResolvedDeclaration? =
    when {
        (resolved.psi as? KtProperty)?.isLocal == true -> IntellijK2ResolvedDeclaration.InvokeReceiver
        resolved is KaNamedFunctionSymbol && confirmsBuiltinFunctionInvoke(resolved) ->
            functionValueInvocation(call, invoked)
        else -> null
    }

private fun KaSession.resolvedInvocationParameter(
    call: KtCallExpression,
    resolved: KaSymbol,
): KaValueParameterSymbol? {
    return when (resolved) {
        is KaValueParameterSymbol -> resolved
        else -> {
            val expression =
                if ((call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "invoke")
                    (call.parent as? KtQualifiedExpression)
                        ?.takeIf { it.selectorExpression === call }
                        ?.receiverExpression
                else call.calleeExpression
            var unwrapped = expression
            while (unwrapped is KtParenthesizedExpression) unwrapped = unwrapped.expression
            val name = unwrapped as? KtNameReferenceExpression ?: return null
            name.references
                .filterIsInstance<KtReference>()
                .mapNotNull { it.resolveToSymbol() as? KaValueParameterSymbol }
                .singleOrNull() ?: return null
        }
    }
}

internal fun KaSession.confirmsBuiltinFunctionInvoke(symbol: KaNamedFunctionSymbol): Boolean {
    val id = symbol.callableId ?: return false
    if (
        symbol.name.asString() != "invoke" ||
            id.packageName.asString() !in setOf("kotlin", "kotlin.coroutines") ||
            id.className?.asString()?.matches(Regex("(?:Suspend)?Function[0-9]+")) != true
    )
        return false
    return symbol.containingModule is KaBuiltinsModule ||
        (symbol.containingModule is KaLibraryModule && symbol.origin == KaSymbolOrigin.LIBRARY)
}

/** A complex invoke receiver can have no reference symbol while its complete call resolves exactly. */
internal fun KaSession.resolvedFunctionValueInvocation(reference: KtReference): KaNamedFunctionSymbol? {
    val call = reference.element.parent as? KtCallExpression ?: return null
    if (call.calleeExpression !== reference.element) return null
    val invoked = call.resolveCall()?.signature?.symbol as? KaNamedFunctionSymbol ?: return null
    return invoked.takeIf { confirmsBuiltinFunctionInvoke(it) }
}

/** Preserves the compiler binding while assigning value proof to the exact whole invocation. */
internal fun KaSession.functionValueInvocation(
    reference: KtReference,
    symbol: KaNamedFunctionSymbol,
): IntellijK2ResolvedDeclaration {
    val call = reference.element.parent as? KtCallExpression ?: return IntellijK2ResolvedDeclaration.Unresolved
    return functionValueInvocation(call, symbol)
}

private fun KaSession.functionValueInvocation(
    call: KtCallExpression,
    symbol: KaNamedFunctionSymbol,
): IntellijK2ResolvedDeclaration =
    when (val projected = sourceLessCallable(symbol)) {
        is IntellijK2ResolvedDeclaration.SourceLess ->
            IntellijK2ResolvedDeclaration.FunctionInvocation(call, projected.callable)
        else -> projected
    }

/** Module and origin prove a known source boundary; missing PSI alone proves nothing about membership. */
internal fun KaSession.sourceLessCallable(symbol: KaSymbol): IntellijK2ResolvedDeclaration {
    val projected =
        when (val result = symbol.compilerProjection(this)) {
            is IntellijCompilerProjectionResult.Projected -> result.projection
            is IntellijCompilerProjectionResult.LocalRejected,
            IntellijCompilerProjectionResult.Unsupported ->
                return unsupported(IntellijResolvedCallableFailure.COMPILER_IDENTITY_UNAVAILABLE)
        }
    val module = symbol.containingModule
    val kind =
        when (module) {
            is KaBuiltinsModule -> SourceLessCallableModuleKind.BUILTINS
            is KaLibraryModule -> SourceLessCallableModuleKind.LIBRARY
            else -> return unsupported(IntellijResolvedCallableFailure.UNSUPPORTED_MODULE)
        }
    val moduleName =
        when (module) {
            is KaBuiltinsModule -> module.moduleDescription
            is KaLibraryModule -> module.libraryName
            else -> return unsupported(IntellijResolvedCallableFailure.UNSUPPORTED_MODULE)
        }
    val name =
        when (val parsed = SourceLessCallableModuleName.parse(moduleName)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return unsupported(IntellijResolvedCallableFailure.MODULE_IDENTITY_UNAVAILABLE)
        }
    return when (
        val admitted =
            SourceLessCallable.fromCompiler(
                projected.signature,
                projected.kind,
                symbol.origin.sourceLessOrigin(),
                kind,
                name,
            )
    ) {
        is Refinement.Refined -> IntellijK2ResolvedDeclaration.SourceLess(admitted.value)
        is Refinement.Rejected ->
            when (admitted.failure) {
                SourceLessCallableFailure.UNSUPPORTED_ORIGIN ->
                    unsupported(IntellijResolvedCallableFailure.UNSUPPORTED_ORIGIN)
                SourceLessCallableFailure.INVALID_MODULE_NAME ->
                    unsupported(IntellijResolvedCallableFailure.MODULE_IDENTITY_UNAVAILABLE)
                SourceLessCallableFailure.NOT_CALLABLE ->
                    unsupported(IntellijResolvedCallableFailure.COMPILER_IDENTITY_UNAVAILABLE)
            }
    }
}

private fun unsupported(cause: IntellijResolvedCallableFailure) = IntellijK2ResolvedDeclaration.Unsupported(cause)

private fun KaSymbolOrigin.sourceLessOrigin(): SourceLessCallableOrigin =
    when (this) {
        KaSymbolOrigin.SOURCE -> SourceLessCallableOrigin.SOURCE
        KaSymbolOrigin.SOURCE_MEMBER_GENERATED -> SourceLessCallableOrigin.SOURCE_MEMBER_GENERATED
        KaSymbolOrigin.LIBRARY -> SourceLessCallableOrigin.LIBRARY
        KaSymbolOrigin.JAVA_SOURCE -> SourceLessCallableOrigin.JAVA_SOURCE
        KaSymbolOrigin.JAVA_LIBRARY -> SourceLessCallableOrigin.JAVA_LIBRARY
        KaSymbolOrigin.SAM_CONSTRUCTOR -> SourceLessCallableOrigin.SAM_CONSTRUCTOR
        KaSymbolOrigin.TYPEALIASED_CONSTRUCTOR -> SourceLessCallableOrigin.TYPEALIASED_CONSTRUCTOR
        KaSymbolOrigin.INTERSECTION_OVERRIDE -> SourceLessCallableOrigin.INTERSECTION_OVERRIDE
        KaSymbolOrigin.SUBSTITUTION_OVERRIDE -> SourceLessCallableOrigin.SUBSTITUTION_OVERRIDE
        KaSymbolOrigin.DELEGATED -> SourceLessCallableOrigin.DELEGATED
        KaSymbolOrigin.JAVA_SYNTHETIC_PROPERTY -> SourceLessCallableOrigin.JAVA_SYNTHETIC_PROPERTY
        KaSymbolOrigin.PROPERTY_BACKING_FIELD -> SourceLessCallableOrigin.PROPERTY_BACKING_FIELD
        KaSymbolOrigin.PLUGIN -> SourceLessCallableOrigin.PLUGIN
        KaSymbolOrigin.JS_DYNAMIC -> SourceLessCallableOrigin.JS_DYNAMIC
        KaSymbolOrigin.NATIVE_FORWARD_DECLARATION -> SourceLessCallableOrigin.NATIVE_FORWARD_DECLARATION
    }
