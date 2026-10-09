@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackDependencyContract
import io.github.amichne.kast.relation.contract.CallbackDependencyContractProvenance
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.DependencyClassDigest
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.security.MessageDigest
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractCallsInPlaceContractEffectDeclaration
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractInvocationKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression

/** Reads only the resolved binary's declared effect. Never enumerates or analyzes a decompiled dependency body. */
internal fun readCallbackDependencyContract(
    context: IntellijCallbackFlowContext,
    call: KtCallElement,
    expression: KtExpression,
): CallbackBindingPreparation {
    context.observation.count(IntellijReadCounter.CALLBACK_DEPENDENCY_CONTRACT_READS)
    val result = dependencyContract(context, call, expression)
    context.observation.count(
        if (result is CallbackBindingPreparation.DependencyContract)
            IntellijReadCounter.CALLBACK_DEPENDENCY_CONTRACTS_ADMITTED
        else IntellijReadCounter.CALLBACK_DEPENDENCY_CONTRACTS_REJECTED
    )
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
        analyze(call) { binaryContract(context, call, expression) }
            ?: return CallbackBindingPreparation.Unavailable(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
    val occurrence =
        context.occurrence(call.valueInvocationExpression())
            ?: return CallbackBindingPreparation.Unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    val owner =
        context.owner(call)
            ?: return CallbackBindingPreparation.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    return when (
        val result =
            CallbackDependencyContract.fromCompiler(
                context.scope.request.subject.lease.identity,
                occurrence,
                owner,
                native.target,
                native.position,
                native.digest,
                CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                CallbackDependencyInvocationKind.EXACTLY_ONCE,
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
): NativeDependencyContract? {
    val resolved = call.resolveCall() ?: return null
    val symbol = resolved.signature.symbol as? KaNamedFunctionSymbol ?: return null
    if (symbol.origin != KaSymbolOrigin.LIBRARY) return null
    val parameter = resolved.valueArgumentMapping[expression]?.symbol ?: return null
    if (parameter.returnType !is KaFunctionType || !symbol.exactlyOnce(parameter)) return null
    val position =
        when (val parsed = ValueArgumentPosition.parse(symbol.valueParameters.indexOf(parameter))) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return null
        }
    val declaration = symbol.psi as? PsiNamedElement ?: return null
    val file = declaration.containingFile?.virtualFile ?: return null
    val digest = file.contractClassDigest() ?: return null
    val evidence = contractTarget(context, symbol, declaration, file) ?: return null
    return NativeDependencyContract(evidence, position, digest)
}

private fun KaNamedFunctionSymbol.exactlyOnce(
    parameter: org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
): Boolean {
    val effects =
        contractEffects.filterIsInstance<KaContractCallsInPlaceContractEffectDeclaration>().filter {
            it.valueParameterReference.symbol == parameter
        }
    return effects.size == 1 && effects.single().invocationKind == KaContractInvocationKind.EXACTLY_ONCE
}

private fun com.intellij.openapi.vfs.VirtualFile.contractClassDigest(): DependencyClassDigest? {
    if (extension != "class" || length !in 1..MAX_CONTRACT_CLASS_BYTES) return null
    val stamp = modificationStamp
    val content =
        try {
            inputStream.use { it.readNBytes((MAX_CONTRACT_CLASS_BYTES + 1).toInt()) }
        } catch (_: java.io.IOException) {
            return null
        }
    if (content.size.toLong() !in 1..MAX_CONTRACT_CLASS_BYTES || modificationStamp != stamp) return null
    return when (
        val parsed =
            DependencyClassDigest.parse(
                MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
            )
    ) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> null
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
)

private const val MAX_CONTRACT_CLASS_BYTES = 1_048_576L
