@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.*

internal enum class NativeCompilerTypeProof {
    ADMITTED,
    ERROR_TYPE,
    WORK_LIMIT_REACHED,
    UNSUPPORTED_TYPE,
}

/** Inspect compiler type structure before rendering, including nested arguments, bounds and flexible types. */
internal fun nativeCompilerTypeProof(types: List<KaType>): NativeCompilerTypeProof =
    nativeCompilerTypeProof(types.asSequence())

internal fun nativeCompilerTypeProof(types: Sequence<KaType>): NativeCompilerTypeProof {
    val pending = ArrayDeque<Pair<KaType, Int>>()
    for (type in types) {
        if (pending.size >= 256) return NativeCompilerTypeProof.WORK_LIMIT_REACHED
        pending.addLast(type to 0)
    }
    val parameters = hashSetOf<KaTypeParameterSymbol>()
    var examined = 0
    while (pending.isNotEmpty()) {
        val (type, depth) = pending.removeFirst()
        if (++examined > 256 || depth > 32) return NativeCompilerTypeProof.WORK_LIMIT_REACHED
        val children =
            when (type) {
                is KaErrorType -> return NativeCompilerTypeProof.ERROR_TYPE
                is KaFlexibleType -> listOf(type.lowerBound, type.upperBound)
                is KaDefinitelyNotNullType -> listOf(type.original)
                is KaIntersectionType -> type.conjuncts
                is KaTypeParameterType -> if (parameters.add(type.symbol)) type.symbol.upperBounds else emptyList()
                is KaCapturedType ->
                    when (val projection = type.projection) {
                        is KaStarTypeProjection -> emptyList()
                        is KaTypeArgumentWithVariance -> listOf(projection.type)
                        else -> return NativeCompilerTypeProof.UNSUPPORTED_TYPE
                    }
                is KaClassType -> {
                    if (type.qualifiers.size > 256 - examined) return NativeCompilerTypeProof.WORK_LIMIT_REACHED
                    examined += type.qualifiers.size
                    val projected = mutableListOf<KaType>()
                    val projections =
                        type.typeArguments.asSequence() +
                            type.qualifiers.asSequence().flatMap { it.typeArguments.asSequence() }
                    for (projection in projections) {
                        if (++examined > 256) return NativeCompilerTypeProof.WORK_LIMIT_REACHED
                        when (projection) {
                            is KaStarTypeProjection -> Unit
                            is KaTypeArgumentWithVariance -> projected.add(projection.type)
                            else -> return NativeCompilerTypeProof.UNSUPPORTED_TYPE
                        }
                    }
                    projected
                }
                is KaDynamicType -> emptyList()
                else -> return NativeCompilerTypeProof.UNSUPPORTED_TYPE
            }
        for (child in children) {
            if (pending.size >= 256) return NativeCompilerTypeProof.WORK_LIMIT_REACHED
            pending.addLast(child to depth + 1)
        }
    }
    return NativeCompilerTypeProof.ADMITTED
}
