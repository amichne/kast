@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

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

private const val MAX_COMPILER_TYPE_WORK = 256
private const val MAX_COMPILER_TYPE_DEPTH = 32

internal fun defaultRelationCompilerTypeAllowance(): WorkAllowance.Bounded =
    when (val limit = WorkUnitLimit.parse(MAX_COMPILER_TYPE_WORK.toLong())) {
        is Refinement.Refined -> WorkAllowance.Bounded(limit.value)
        is Refinement.Rejected -> error("The fixed compiler type allowance must be positive")
    }

/** Inspect compiler structure before rendering. Native types remain inside the analysis session. */
internal fun nativeCompilerTypeProof(
    types: List<KaType>,
    workAllowance: WorkAllowance = defaultRelationCompilerTypeAllowance(),
): NativeCompilerTypeProof = nativeCompilerTypeProof(types.asSequence(), workAllowance)

internal fun nativeCompilerTypeProof(
    types: Sequence<KaType>,
    workAllowance: WorkAllowance = defaultRelationCompilerTypeAllowance(),
): NativeCompilerTypeProof =
    when (val result = IntellijCompilerTypeProof<Nothing>(workAllowance) { Refinement.Refined(Unit) }.inspect(types)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> result.failure
    }

/** Shared work admission surrounds every compiler-type step; the fixed structural ceiling remains local. */
internal fun <Failure> nativeCompilerTypeProof(
    types: List<KaType>,
    permit: () -> Refinement<Unit, Failure>,
): Refinement<NativeCompilerTypeProof, Failure> =
    IntellijCompilerTypeProof(defaultRelationCompilerTypeAllowance(), permit).inspect(types.asSequence())

private class IntellijCompilerTypeProof<Failure>(
    private val workAllowance: WorkAllowance,
    private val permit: () -> Refinement<Unit, Failure>,
) {
    private val pending = ArrayDeque<Pair<KaType, Int>>()
    private val parameters = hashSetOf<KaTypeParameterSymbol>()
    private val seen = hashSetOf<KaType>()
    private var examined = 0L
    private val work =
        when (workAllowance) {
            WorkAllowance.Unlimited -> WorkAllowance.Unlimited
            is WorkAllowance.Bounded -> workAllowance.intersect(defaultRelationCompilerTypeAllowance())
        }

    fun inspect(types: Sequence<KaType>): Refinement<NativeCompilerTypeProof, Failure> {
        val roots = enqueue(types, 0)
        if (roots != admitted()) return roots
        while (pending.isNotEmpty()) {
            when (val allowed = permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return allowed
            }
            val (type, depth) = pending.removeFirst()
            if (!spend(1L) || workAllowance is WorkAllowance.Bounded && depth > MAX_COMPILER_TYPE_DEPTH)
                return Refinement.Refined(NativeCompilerTypeProof.WORK_LIMIT_REACHED)
            val proof = visit(type, depth)
            if (proof != admitted()) return proof
        }
        return admitted()
    }

    private fun spend(count: Long): Boolean {
        ProgressManager.checkCanceled()
        examined = if (count > Long.MAX_VALUE - examined) Long.MAX_VALUE else examined + count
        return work.permits(examined)
    }

    private fun enqueue(types: Sequence<KaType>, depth: Int): Refinement<NativeCompilerTypeProof, Failure> {
        val iterator = types.iterator()
        while (true) {
            ProgressManager.checkCanceled()
            when (val allowed = permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return allowed
            }
            if (!iterator.hasNext()) return admitted()
            val type = iterator.next()
            if (workAllowance == WorkAllowance.Unlimited && !seen.add(type)) continue
            if (!work.permits(pending.size.toLong() + 1L))
                return Refinement.Refined(NativeCompilerTypeProof.WORK_LIMIT_REACHED)
            pending.addLast(type to depth)
        }
    }

    private fun visit(type: KaType, depth: Int): Refinement<NativeCompilerTypeProof, Failure> =
        when (type) {
            is KaErrorType -> Refinement.Refined(NativeCompilerTypeProof.ERROR_TYPE)
            is KaFlexibleType -> enqueue(sequenceOf(type.lowerBound, type.upperBound), depth + 1)
            is KaDefinitelyNotNullType -> enqueue(sequenceOf(type.original), depth + 1)
            is KaIntersectionType -> enqueue(type.conjuncts.asSequence(), depth + 1)
            is KaTypeParameterType ->
                if (parameters.add(type.symbol)) enqueue(type.symbol.upperBounds.asSequence(), depth + 1)
                else admitted()
            is KaCapturedType ->
                when (val projection = type.projection) {
                    is KaStarTypeProjection -> admitted()
                    is KaTypeArgumentWithVariance -> enqueue(sequenceOf(projection.type), depth + 1)
                    else -> Refinement.Refined(NativeCompilerTypeProof.UNSUPPORTED_TYPE)
                }
            is KaClassType -> visitClass(type, depth)
            is KaDynamicType -> admitted()
            else -> Refinement.Refined(NativeCompilerTypeProof.UNSUPPORTED_TYPE)
        }

    private fun visitClass(type: KaClassType, depth: Int): Refinement<NativeCompilerTypeProof, Failure> {
        if (!spend(type.qualifiers.size.toLong())) return Refinement.Refined(NativeCompilerTypeProof.WORK_LIMIT_REACHED)
        val arguments =
            type.typeArguments.asSequence() + type.qualifiers.asSequence().flatMap { it.typeArguments.asSequence() }
        val projections = arguments.iterator()
        while (true) {
            when (val allowed = permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return allowed
            }
            if (!projections.hasNext()) return admitted()
            if (!spend(1L)) return Refinement.Refined(NativeCompilerTypeProof.WORK_LIMIT_REACHED)
            val proof =
                when (val projection = projections.next()) {
                    is KaStarTypeProjection -> admitted()
                    is KaTypeArgumentWithVariance -> enqueue(sequenceOf(projection.type), depth + 1)
                    else -> Refinement.Refined(NativeCompilerTypeProof.UNSUPPORTED_TYPE)
                }
            if (proof != admitted()) return proof
        }
    }

    private fun admitted(): Refinement<NativeCompilerTypeProof, Failure> =
        Refinement.Refined(NativeCompilerTypeProof.ADMITTED)
}

/** Inspect exactly the type facts encoded by a qualified containing declaration's signature. */
internal fun KaSymbol.localOwnerCompilerTypeProof(
    workAllowance: WorkAllowance = defaultRelationCompilerTypeAllowance()
): NativeCompilerTypeProof {
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
            else -> return NativeCompilerTypeProof.UNSUPPORTED_TYPE
        }
    return nativeCompilerTypeProof(facts, workAllowance)
}
