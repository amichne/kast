@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol

private const val MAX_LOCAL_RELATION_TYPE_PARAMETERS = 256

internal fun KaSymbol.localRelationCompilerTypeProof(): Refinement<Unit, LocalDeclarationProjectionFailure> {
    if (this is KaNamedFunctionSymbol && typeParameters.size > MAX_LOCAL_RELATION_TYPE_PARAMETERS)
        return Refinement.Rejected(LocalDeclarationProjectionFailure.WorkLimitReached)
    val facts =
        when (this) {
            is KaNamedFunctionSymbol ->
                sequenceOf(returnType) +
                    listOfNotNull(receiverParameter?.returnType).asSequence() +
                    contextReceivers.asSequence().map { it.type } +
                    valueParameters.asSequence().map { it.returnType } +
                    typeParameters.asSequence().flatMap { it.upperBounds.asSequence() }
            is KaLocalVariableSymbol -> sequenceOf(returnType)
            else -> emptySequence()
        }
    return nativeCompilerTypeProof(facts).asLocalRelationTypeProof()
}

internal fun NativeCompilerTypeProof.asLocalRelationTypeProof(): Refinement<Unit, LocalDeclarationProjectionFailure> =
    when (this) {
        NativeCompilerTypeProof.ADMITTED -> Refinement.Refined(Unit)
        NativeCompilerTypeProof.ERROR_TYPE -> Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerTypeError)
        NativeCompilerTypeProof.WORK_LIMIT_REACHED ->
            Refinement.Rejected(LocalDeclarationProjectionFailure.WorkLimitReached)
        NativeCompilerTypeProof.UNSUPPORTED_TYPE ->
            Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerTypeUnsupported)
    }
