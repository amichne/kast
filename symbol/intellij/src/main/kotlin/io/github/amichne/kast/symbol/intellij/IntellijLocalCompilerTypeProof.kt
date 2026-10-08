@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaCapturedType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaDynamicType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaIntersectionType
import org.jetbrains.kotlin.analysis.api.types.KaStarTypeProjection
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType

/** Inspect compiler type structure before rendering, including nested arguments, bounds and flexible types. */
internal fun localCompilerTypeProof(types: List<KaType>): LocalCompilerTypeProof =
    localCompilerTypeProof(types.asSequence())

private const val MAX_LOCAL_COMPILER_TYPE_WORK = 256
private const val MAX_LOCAL_COMPILER_TYPE_DEPTH = 32

internal fun localCompilerTypeProof(types: Sequence<KaType>): LocalCompilerTypeProof =
    IntellijLocalCompilerTypeProof().inspect(types)

private class IntellijLocalCompilerTypeProof {
    private val pending = ArrayDeque<Pair<KaType, Int>>()
    private val parameters = hashSetOf<KaTypeParameterSymbol>()
    private var examined = 0

    fun inspect(types: Sequence<KaType>): LocalCompilerTypeProof {
        val roots = enqueue(types, 0)
        if (roots != LocalCompilerTypeProof.ADMITTED) return roots
        while (pending.isNotEmpty()) {
            val (type, depth) = pending.removeFirst()
            if (++examined > MAX_LOCAL_COMPILER_TYPE_WORK || depth > MAX_LOCAL_COMPILER_TYPE_DEPTH)
                return LocalCompilerTypeProof.WORK_LIMIT_REACHED
            val children =
                when (val proof = children(type)) {
                    is Refinement.Refined -> proof.value
                    is Refinement.Rejected -> return proof.failure
                }
            val admitted = enqueue(children.asSequence(), depth + 1)
            if (admitted != LocalCompilerTypeProof.ADMITTED) return admitted
        }
        return LocalCompilerTypeProof.ADMITTED
    }

    private fun enqueue(types: Sequence<KaType>, depth: Int): LocalCompilerTypeProof {
        for (type in types) {
            if (pending.size >= MAX_LOCAL_COMPILER_TYPE_WORK) return LocalCompilerTypeProof.WORK_LIMIT_REACHED
            pending.addLast(type to depth)
        }
        return LocalCompilerTypeProof.ADMITTED
    }

    private fun children(type: KaType): Refinement<List<KaType>, LocalCompilerTypeProof> =
        when (type) {
            is KaErrorType -> Refinement.Rejected(LocalCompilerTypeProof.ERROR_TYPE)
            is KaFlexibleType -> Refinement.Refined(listOf(type.lowerBound, type.upperBound))
            is KaDefinitelyNotNullType -> Refinement.Refined(listOf(type.original))
            is KaIntersectionType -> Refinement.Refined(type.conjuncts)
            is KaTypeParameterType ->
                Refinement.Refined(if (parameters.add(type.symbol)) type.symbol.upperBounds else emptyList())
            is KaCapturedType -> capturedChildren(type)
            is KaClassType -> classChildren(type)
            is KaDynamicType -> Refinement.Refined(emptyList())
            else -> Refinement.Rejected(LocalCompilerTypeProof.UNSUPPORTED_TYPE)
        }

    private fun capturedChildren(type: KaCapturedType): Refinement<List<KaType>, LocalCompilerTypeProof> =
        when (val projection = type.projection) {
            is KaStarTypeProjection -> Refinement.Refined(emptyList())
            is KaTypeArgumentWithVariance -> Refinement.Refined(listOf(projection.type))
            else -> Refinement.Rejected(LocalCompilerTypeProof.UNSUPPORTED_TYPE)
        }

    private fun classChildren(type: KaClassType): Refinement<List<KaType>, LocalCompilerTypeProof> {
        if (type.qualifiers.size > MAX_LOCAL_COMPILER_TYPE_WORK - examined)
            return Refinement.Rejected(LocalCompilerTypeProof.WORK_LIMIT_REACHED)
        examined += type.qualifiers.size
        val projected = mutableListOf<KaType>()
        val projections =
            type.typeArguments.asSequence() + type.qualifiers.asSequence().flatMap { it.typeArguments.asSequence() }
        for (projection in projections) {
            if (++examined > MAX_LOCAL_COMPILER_TYPE_WORK)
                return Refinement.Rejected(LocalCompilerTypeProof.WORK_LIMIT_REACHED)
            when (projection) {
                is KaStarTypeProjection -> Unit
                is KaTypeArgumentWithVariance -> projected.add(projection.type)
                else -> return Refinement.Rejected(LocalCompilerTypeProof.UNSUPPORTED_TYPE)
            }
        }
        return Refinement.Refined(projected)
    }
}

/** Inspect exactly the type facts encoded by a qualified containing declaration's signature. */
internal fun KaSymbol.localOwnerCompilerTypeProof(): LocalCompilerTypeProof {
    val facts =
        when (this) {
            is KaFunctionSymbol ->
                listOfNotNull(receiverParameter?.returnType).asSequence() +
                    contextReceivers.asSequence().map { it.type } +
                    valueParameters.asSequence().map { it.returnType }
            is KaKotlinPropertySymbol ->
                sequenceOf(returnType) +
                    listOfNotNull(receiverParameter?.returnType).asSequence() +
                    contextReceivers.asSequence().map { it.type }
            is KaClassLikeSymbol -> emptySequence()
            else -> return LocalCompilerTypeProof.UNSUPPORTED_TYPE
        }
    return localCompilerTypeProof(facts)
}
