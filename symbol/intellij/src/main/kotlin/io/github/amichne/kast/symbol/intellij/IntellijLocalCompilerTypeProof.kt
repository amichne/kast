@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.kernel.WorkUnitLimit
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
internal fun localCompilerTypeProof(
    types: List<KaType>,
    workAllowance: WorkAllowance = defaultLocalCompilerTypeAllowance(),
): LocalCompilerTypeProof = localCompilerTypeProof(types.asSequence(), workAllowance)

private const val MAX_LOCAL_COMPILER_TYPE_WORK = 256
private const val MAX_LOCAL_COMPILER_TYPE_DEPTH = 32

internal fun defaultLocalCompilerTypeAllowance(): WorkAllowance =
    when (val limit = WorkUnitLimit.parse(MAX_LOCAL_COMPILER_TYPE_WORK.toLong())) {
        is Refinement.Refined -> WorkAllowance.Bounded(limit.value)
        is Refinement.Rejected -> error("Invalid local compiler type allowance")
    }

internal fun localCompilerTypeProof(
    types: Sequence<KaType>,
    workAllowance: WorkAllowance = defaultLocalCompilerTypeAllowance(),
): LocalCompilerTypeProof = IntellijLocalCompilerTypeProof(workAllowance).inspect(types)

private class IntellijLocalCompilerTypeProof(requested: WorkAllowance) {
    private val allowance =
        when (requested) {
            is WorkAllowance.Bounded -> requested.intersect(defaultLocalCompilerTypeAllowance())
            WorkAllowance.Unlimited -> WorkAllowance.Unlimited
        }
    private val seen = hashSetOf<KaType>()
    private val pending = ArrayDeque<Pair<KaType, Int>>()
    private val parameters = hashSetOf<KaTypeParameterSymbol>()
    private var examined = 0L

    fun inspect(types: Sequence<KaType>): LocalCompilerTypeProof {
        val roots = enqueue(types, 0)
        if (roots != LocalCompilerTypeProof.ADMITTED) return roots
        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val (type, depth) = pending.removeFirst()
            if (
                !allowance.permits(++examined) ||
                    allowance is WorkAllowance.Bounded && depth > MAX_LOCAL_COMPILER_TYPE_DEPTH
            )
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
            ProgressManager.checkCanceled()
            if (allowance == WorkAllowance.Unlimited && !seen.add(type)) continue
            if (!allowance.hasCapacity(pending.size.toLong())) return LocalCompilerTypeProof.WORK_LIMIT_REACHED
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
        if (!allowance.permits(examined + type.qualifiers.size))
            return Refinement.Rejected(LocalCompilerTypeProof.WORK_LIMIT_REACHED)
        examined += type.qualifiers.size
        val projected = mutableListOf<KaType>()
        val projections =
            type.typeArguments.asSequence() + type.qualifiers.asSequence().flatMap { it.typeArguments.asSequence() }
        for (projection in projections) {
            ProgressManager.checkCanceled()
            if (!allowance.permits(++examined)) return Refinement.Rejected(LocalCompilerTypeProof.WORK_LIMIT_REACHED)
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
internal fun KaSymbol.localOwnerCompilerTypeProof(
    workAllowance: WorkAllowance = defaultLocalCompilerTypeAllowance()
): LocalCompilerTypeProof {
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
    return localCompilerTypeProof(facts, workAllowance)
}
