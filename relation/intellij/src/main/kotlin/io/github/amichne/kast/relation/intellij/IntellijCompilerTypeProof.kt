@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

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

private const val MAX_COMPILER_TYPE_WORK = 256
private const val MAX_COMPILER_TYPE_DEPTH = 32

/** Inspect compiler structure before rendering; no native type escapes this bounded inspection. */
internal fun nativeCompilerTypeProof(types: List<KaType>): NativeCompilerTypeProof =
    nativeCompilerTypeProof(types.asSequence())

internal fun nativeCompilerTypeProof(types: Sequence<KaType>): NativeCompilerTypeProof =
    IntellijCompilerTypeProof().inspect(types)

private class IntellijCompilerTypeProof {
    private val pending = ArrayDeque<Pair<KaType, Int>>()
    private val parameters = hashSetOf<KaTypeParameterSymbol>()
    private var examined = 0

    fun inspect(types: Sequence<KaType>): NativeCompilerTypeProof {
        val roots = enqueue(types, 0)
        if (roots != NativeCompilerTypeProof.ADMITTED) return roots
        while (pending.isNotEmpty()) {
            val (type, depth) = pending.removeFirst()
            if (++examined > MAX_COMPILER_TYPE_WORK || depth > MAX_COMPILER_TYPE_DEPTH)
                return NativeCompilerTypeProof.WORK_LIMIT_REACHED
            val proof = visit(type, depth)
            if (proof != NativeCompilerTypeProof.ADMITTED) return proof
        }
        return NativeCompilerTypeProof.ADMITTED
    }

    private fun enqueue(types: Sequence<KaType>, depth: Int): NativeCompilerTypeProof {
        for (type in types) {
            if (pending.size >= MAX_COMPILER_TYPE_WORK) return NativeCompilerTypeProof.WORK_LIMIT_REACHED
            pending.addLast(type to depth)
        }
        return NativeCompilerTypeProof.ADMITTED
    }

    private fun visit(type: KaType, depth: Int): NativeCompilerTypeProof =
        when (type) {
            is KaErrorType -> NativeCompilerTypeProof.ERROR_TYPE
            is KaFlexibleType -> enqueue(sequenceOf(type.lowerBound, type.upperBound), depth + 1)
            is KaDefinitelyNotNullType -> enqueue(sequenceOf(type.original), depth + 1)
            is KaIntersectionType -> enqueue(type.conjuncts.asSequence(), depth + 1)
            is KaTypeParameterType ->
                if (parameters.add(type.symbol)) enqueue(type.symbol.upperBounds.asSequence(), depth + 1)
                else NativeCompilerTypeProof.ADMITTED
            is KaCapturedType ->
                when (val projection = type.projection) {
                    is KaStarTypeProjection -> NativeCompilerTypeProof.ADMITTED
                    is KaTypeArgumentWithVariance -> enqueue(sequenceOf(projection.type), depth + 1)
                    else -> NativeCompilerTypeProof.UNSUPPORTED_TYPE
                }
            is KaClassType -> visitClass(type, depth)
            is KaDynamicType -> NativeCompilerTypeProof.ADMITTED
            else -> NativeCompilerTypeProof.UNSUPPORTED_TYPE
        }

    private fun visitClass(type: KaClassType, depth: Int): NativeCompilerTypeProof {
        if (type.qualifiers.size > MAX_COMPILER_TYPE_WORK - examined) return NativeCompilerTypeProof.WORK_LIMIT_REACHED
        examined += type.qualifiers.size
        val projections =
            type.typeArguments.asSequence() + type.qualifiers.asSequence().flatMap { it.typeArguments.asSequence() }
        for (projection in projections) {
            if (++examined > MAX_COMPILER_TYPE_WORK) return NativeCompilerTypeProof.WORK_LIMIT_REACHED
            val proof =
                when (projection) {
                    is KaStarTypeProjection -> NativeCompilerTypeProof.ADMITTED
                    is KaTypeArgumentWithVariance -> enqueue(sequenceOf(projection.type), depth + 1)
                    else -> NativeCompilerTypeProof.UNSUPPORTED_TYPE
                }
            if (proof != NativeCompilerTypeProof.ADMITTED) return proof
        }
        return NativeCompilerTypeProof.ADMITTED
    }
}
