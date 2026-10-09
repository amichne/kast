@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol

private const val MAX_LOCAL_SOURCE_TYPE_PARAMETERS = 256

internal fun KaSymbol.localSourceCompilerTypeProof(): Refinement<Unit, LocalDeclarationProjectionFailure> {
    if (this is KaNamedFunctionSymbol && typeParameters.size > MAX_LOCAL_SOURCE_TYPE_PARAMETERS)
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
            is KaAnonymousObjectSymbol -> superTypes.asSequence()
            else -> emptySequence()
        }
    return localCompilerTypeProof(facts).asLocalSourceTypeProof()
}

internal fun LocalCompilerTypeProof.asLocalSourceTypeProof(): Refinement<Unit, LocalDeclarationProjectionFailure> =
    when (this) {
        LocalCompilerTypeProof.ADMITTED -> Refinement.Refined(Unit)
        LocalCompilerTypeProof.ERROR_TYPE -> Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerTypeError)
        LocalCompilerTypeProof.WORK_LIMIT_REACHED ->
            Refinement.Rejected(LocalDeclarationProjectionFailure.WorkLimitReached)
        LocalCompilerTypeProof.UNSUPPORTED_TYPE ->
            Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerTypeUnsupported)
    }
