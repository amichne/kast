@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackDeferredDependency
import io.github.amichne.kast.relation.contract.CallbackDependencyContract
import io.github.amichne.kast.relation.contract.CallbackDependencyContractProvenance
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.DependencyClassDigest
import io.github.amichne.kast.relation.contract.KotlinLazyBinaryEvidence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.security.MessageDigest
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractCallsInPlaceContractEffectDeclaration
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression

/** Reads a declared effect or an exact reviewed lazy model. Dependency PSI bodies never supply proof. */
internal fun readCallbackDependencyContract(
    context: IntellijCallbackFlowContext,
    call: KtCallElement,
    expression: KtExpression,
): CallbackBindingPreparation {
    context.observation.count(IntellijReadCounter.CALLBACK_DEPENDENCY_CONTRACT_READS)
    val result = dependencyContract(context, call, expression)
    context.observation.count(
        if (
            result is CallbackBindingPreparation.DependencyContract ||
                result is CallbackBindingPreparation.DeferredDependency
        )
            IntellijReadCounter.CALLBACK_DEPENDENCY_CONTRACTS_ADMITTED
        else IntellijReadCounter.CALLBACK_DEPENDENCY_CONTRACTS_REJECTED
    )
    if (result is CallbackBindingPreparation.Unavailable)
        context.observation.terminated(result.cause.supplierTermination())
    return result
}

private fun dependencyContract(
    context: IntellijCallbackFlowContext,
    call: KtCallElement,
    expression: KtExpression,
): CallbackBindingPreparation {
    when (val permit = context.permit()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return CallbackBindingPreparation.Unavailable(permit.failure)
    }
    val native =
        when (val read = context.observation.observedAnalyze(call) { binaryContract(context, call, expression) }) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return CallbackBindingPreparation.Unavailable(read.failure)
        }
    val occurrence =
        context.occurrence(call.valueInvocationExpression())
            ?: return CallbackBindingPreparation.Unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    return when (val effect = native.effect) {
        is NativeDependencyEffect.DeferredLazy ->
            bindDeferred(
                context = context,
                call = call,
                native = native,
                occurrence = occurrence,
                binary = effect.binary,
            )
        is NativeDependencyEffect.CallsInPlace ->
            bindDeclared(
                context = context,
                call = call,
                native = native,
                occurrence = occurrence,
                invocationKind = effect.kind,
            )
    }
}

private fun bindDeferred(
    context: IntellijCallbackFlowContext,
    call: KtCallElement,
    native: NativeDependencyContract,
    occurrence: io.github.amichne.kast.relation.contract.RelationOccurrence,
    binary: KotlinLazyBinaryEvidence,
): CallbackBindingPreparation {
    val owner =
        when (val result = context.deferredSupplier(call, occurrence)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return CallbackBindingPreparation.Unavailable(result.failure)
        }
    return when (
        val result =
            CallbackDeferredDependency.fromCompiler(
                owner = owner,
                target = native.target,
                position = native.position,
                binary = binary,
            )
    ) {
        is Refinement.Refined -> CallbackBindingPreparation.DeferredDependency(result.value)
        is Refinement.Rejected -> CallbackBindingPreparation.ContractRejected(result.failure)
    }
}

private fun bindDeclared(
    context: IntellijCallbackFlowContext,
    call: KtCallElement,
    native: NativeDependencyContract,
    occurrence: io.github.amichne.kast.relation.contract.RelationOccurrence,
    invocationKind: CallbackDependencyInvocationKind,
): CallbackBindingPreparation {
    val owner =
        context.owner(call)
            ?: return CallbackBindingPreparation.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    return when (
        val result =
            CallbackDependencyContract.fromCompiler(
                basis = context.scope.request.subject.lease.identity,
                occurrence = occurrence,
                owner = owner,
                target = native.target,
                position = native.position,
                classDigest = native.digest,
                provenance = CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                invocationKind = invocationKind,
            )
    ) {
        is Refinement.Refined -> CallbackBindingPreparation.DependencyContract(result.value)
        is Refinement.Rejected -> CallbackBindingPreparation.ContractRejected(result.failure)
    }
}

private fun org.jetbrains.kotlin.analysis.api.KaSession.binaryContract(
    context: IntellijCallbackFlowContext,
    call: KtCallElement,
    expression: KtExpression,
): Refinement<NativeDependencyContract, CallbackInvocationFlowCause> {
    val resolved =
        call.resolveCall() ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    val symbol =
        resolved.signature.symbol as? KaNamedFunctionSymbol
            ?: return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_TARGET_UNAVAILABLE)
    if (symbol.origin != KaSymbolOrigin.LIBRARY) return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
    val parameter =
        resolved.valueArgumentMapping[expression]?.symbol
            ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    if (parameter.returnType !is KaFunctionType)
        return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    val effects =
        symbol.contractEffects
            .filterIsInstance<KaContractCallsInPlaceContractEffectDeclaration>()
            .filter { it.valueParameterReference.symbol == parameter }
            .map { it.invocationKind }
    val candidate =
        if (effects.isEmpty()) {
            if (symbol.isLazyInitializer(parameter)) NativeDependencyCandidate.LazyInitializer
            else return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN)
        } else {
            when (val admitted = admitDeclaredCallbackInvocationKind(effects)) {
                is Refinement.Refined -> NativeDependencyCandidate.CallsInPlace(admitted.value)
                is Refinement.Rejected -> return admitted
            }
        }
    val position =
        when (val parsed = ValueArgumentPosition.parse(symbol.valueParameters.indexOf(parameter))) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected ->
                return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    return loadBinaryContract(context = context, symbol = symbol, position = position, candidate = candidate)
}

private fun org.jetbrains.kotlin.analysis.api.KaSession.loadBinaryContract(
    context: IntellijCallbackFlowContext,
    symbol: KaNamedFunctionSymbol,
    position: ValueArgumentPosition,
    candidate: NativeDependencyCandidate,
): Refinement<NativeDependencyContract, CallbackInvocationFlowCause> {
    val declaration =
        symbol.psi as? PsiNamedElement
            ?: return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_TARGET_UNAVAILABLE)
    val file =
        declaration.containingFile?.virtualFile
            ?: return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE)
    val digest =
        when (val read = file.contractClassDigest()) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val evidence =
        contractTarget(context, symbol, declaration, file)
            ?: return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_TARGET_UNAVAILABLE)
    val effect =
        when (candidate) {
            is NativeDependencyCandidate.CallsInPlace -> NativeDependencyEffect.CallsInPlace(candidate.kind)
            NativeDependencyCandidate.LazyInitializer ->
                when (val read = readKotlinLazyBinaryEvidence(context, file, digest)) {
                    is Refinement.Refined -> NativeDependencyEffect.DeferredLazy(read.value)
                    is Refinement.Rejected -> return read
                }
        }
    return Refinement.Refined(
        NativeDependencyContract(target = evidence, position = position, digest = digest, effect = effect)
    )
}

private fun KaNamedFunctionSymbol.isLazyInitializer(
    parameter: org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
): Boolean {
    if (callableId?.asSingleFqName()?.asString() != "kotlin.lazy") return false
    if (receiverParameter != null || contextReceivers.isNotEmpty()) return false
    if (typeParameters.size != 1 || valueParameters.size !in 1..2) return false
    if (valueParameters.last() != parameter || parameter.name.asString() != "initializer") return false
    return (returnType as? KaClassType)?.classId?.asSingleFqName()?.asString() == "kotlin.Lazy"
}

internal fun com.intellij.openapi.vfs.VirtualFile.contractClassDigest():
    Refinement<DependencyClassDigest, CallbackInvocationFlowCause> {
    if (extension != "class" || length !in 1..MAX_CONTRACT_CLASS_BYTES)
        return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE)
    val stamp = modificationStamp
    val content =
        try {
            inputStream.use { it.readNBytes((MAX_CONTRACT_CLASS_BYTES + 1).toInt()) }
        } catch (_: java.io.IOException) {
            return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE)
        }
    if (content.size.toLong() !in 1..MAX_CONTRACT_CLASS_BYTES)
        return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE)
    if (modificationStamp != stamp) return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_CHANGED)
    return when (
        val parsed =
            DependencyClassDigest.parse(
                java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
            )
    ) {
        is Refinement.Refined -> parsed
        is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE)
    }
}

private fun org.jetbrains.kotlin.analysis.api.KaSession.contractTarget(
    context: IntellijCallbackFlowContext,
    symbol: KaNamedFunctionSymbol,
    declaration: PsiNamedElement,
    file: com.intellij.openapi.vfs.VirtualFile,
): CompilerGroundedSymbolEvidence? {
    val detached =
        when (val value = detachRelationFile(file, context.scope.request.subject.lease.workspaceRoot)) {
            is IntellijDetachedRelationFile.Found -> value.identity
            IntellijDetachedRelationFile.Unsupported -> return null
        }
    val projection =
        when (val value = symbol.compilerProjection(this, detached, observation = context.observation)) {
            is IntellijCompilerProjectionResult.Projected -> value.projection
            is IntellijCompilerProjectionResult.LocalRejected,
            IntellijCompilerProjectionResult.Unsupported -> return null
        }
    return when (val value = groundedProjection(declaration, detached, projection)) {
        is IntellijRelationDeclarationProjection.Projected -> value.evidence
        IntellijRelationDeclarationProjection.Unsupported -> null
    }
}

private data class NativeDependencyContract(
    val target: CompilerGroundedSymbolEvidence,
    val position: ValueArgumentPosition,
    val digest: DependencyClassDigest,
    val effect: NativeDependencyEffect,
)

private sealed interface NativeDependencyEffect {
    data class CallsInPlace(val kind: CallbackDependencyInvocationKind) : NativeDependencyEffect

    data class DeferredLazy(val binary: KotlinLazyBinaryEvidence) : NativeDependencyEffect
}

private const val MAX_CONTRACT_CLASS_BYTES = 1_048_576L

private sealed interface NativeDependencyCandidate {
    data class CallsInPlace(val kind: CallbackDependencyInvocationKind) : NativeDependencyCandidate

    data object LazyInitializer : NativeDependencyCandidate
}
