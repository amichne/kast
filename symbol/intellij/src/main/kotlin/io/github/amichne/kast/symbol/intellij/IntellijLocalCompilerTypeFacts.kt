@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol

private const val MAX_LOCAL_COMPILER_TYPE_PARAMETERS = 256

internal fun KaSymbol.localDeclarationCompilerTypeProof(): Refinement<Unit, LocalDeclarationProjectionFailure> {
    if (this is KaNamedFunctionSymbol && typeParameters.size > MAX_LOCAL_COMPILER_TYPE_PARAMETERS)
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
    return localCompilerTypeProof(facts).asLocalDeclarationTypeProof()
}

internal fun LocalCompilerTypeProof.asLocalDeclarationTypeProof(): Refinement<Unit, LocalDeclarationProjectionFailure> =
    when (this) {
        LocalCompilerTypeProof.ADMITTED -> Refinement.Refined(Unit)
        LocalCompilerTypeProof.ERROR_TYPE -> Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerTypeError)
        LocalCompilerTypeProof.WORK_LIMIT_REACHED ->
            Refinement.Rejected(LocalDeclarationProjectionFailure.WorkLimitReached)
        LocalCompilerTypeProof.UNSUPPORTED_TYPE ->
            Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerTypeUnsupported)
    }
