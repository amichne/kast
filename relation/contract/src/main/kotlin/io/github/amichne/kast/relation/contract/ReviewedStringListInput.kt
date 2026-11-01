package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import java.util.Collections

enum class ReviewedDistinctKeyFailure {
    CALLABLE_MISMATCH,
    ARTIFACT_MISMATCH,
    INVOCATION_MISMATCH,
    FORMAL_MISMATCH,
    BASIS_MISMATCH,
    NOT_VARARG,
    UNSUPPORTED_ARGUMENT,
    UNKNOWN_STRING_CONSTANT,
    ARGUMENT_INVENTORY_MISMATCH,
    ARGUMENT_RANGE_MISMATCH,
    CALLBACK_SHAPE_UNSUPPORTED,
    KEY_REFERENCE_MISMATCH,
    TUPLE_MISMATCH,
    DUPLICATE_KEYS,
}

/** Native K2 admission establishes vararg separately from the ambiguous canonical parameter type. */
class ReviewedVarargFormal
private constructor(
    val invocation: ValueInvocation,
    val target: CompilerGroundedSymbolEvidence,
    val position: ValueArgumentPosition,
) {
    val retainedBytes: Long
        get() =
            STRING_PROOF_BYTES.addBytes(invocation.enclosing.detachedTextUnits().multiplyBytes(2))
                .addBytes(invocation.callable.detachedTextUnits().multiplyBytes(2))
                .addBytes(valueExecutionStorageBytes(invocation.executionOwner))

    companion object {
        /** The native adapter calls this only after K2 identifies the selected parameter as vararg. */
        fun fromCompiler(
            invocation: ValueInvocation,
            target: CompilerGroundedSymbolEvidence,
            position: ValueArgumentPosition,
        ): Refinement<ReviewedVarargFormal, ReviewedDistinctKeyFailure> {
            if (!target.isReviewedStringListTarget())
                return distinctKeyRejected(ReviewedDistinctKeyFailure.CALLABLE_MISMATCH)
            if (position.value != 0) return distinctKeyRejected(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
            if (
                invocation.callable.valueIdentity != ValueDeclarationIdentity.fromCompiler(target) ||
                    invocation.signature != target.signature
            )
                return distinctKeyRejected(ReviewedDistinctKeyFailure.INVOCATION_MISMATCH)
            return Refinement.Refined(ReviewedVarargFormal(invocation, target, position))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedVarargFormal &&
            invocation == other.invocation &&
            target == other.target &&
            position == other.position

    override fun hashCode(): Int = listOf(invocation, target, position).hashCode()
}

private fun CompilerGroundedSymbolEvidence.isReviewedStringListTarget(): Boolean {
    val signature = signature as? CanonicalCompilerSignature.Function ?: return false
    val file = file as? SymbolDiscoveryFileIdentity.External ?: return false
    if (kind != CompilerSymbolKind.FUNCTION || !file.url.value.endsWith("!/kotlin/collections/CollectionsKt.class"))
        return false
    if (
        signature.qualifiedIdentity.value != "kotlin.collections.listOf" ||
            signature.receiver != CanonicalCompilerReceiver.Absent
    )
        return false
    return signature.contextReceivers.isEmpty() &&
        signature.valueParameters.map { it.value } == listOf("T") &&
        signature.typeParameterCount.value == 1
}

/** A direct nonspread argument maps to the selected vararg and has a compiler-evaluated String value. */
class ReviewedStringVarargArgument
private constructor(
    val formal: ReviewedVarargFormal,
    val ordinal: ValueArgumentPosition,
    val range: ExactDeclarationTextRange,
    val value: String,
) {
    val retainedBytes: Long
        get() = STRING_PROOF_BYTES.addBytes(formal.retainedBytes).addBytes(value.length.toLong().multiplyBytes(2))

    companion object {
        fun fromCompiler(
            formal: ReviewedVarargFormal,
            ordinal: ValueArgumentPosition,
            range: ExactDeclarationTextRange,
            value: String,
        ): Refinement<ReviewedStringVarargArgument, ReviewedDistinctKeyFailure> =
            if (formal.invocation.range.containsValueRange(range))
                Refinement.Refined(ReviewedStringVarargArgument(formal, ordinal, range, value))
            else distinctKeyRejected(ReviewedDistinctKeyFailure.ARGUMENT_RANGE_MISMATCH)
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedStringVarargArgument &&
            formal == other.formal &&
            ordinal == other.ordinal &&
            range == other.range &&
            value == other.value

    override fun hashCode(): Int = listOf(formal, ordinal, range, value).hashCode()
}

/** Complete direct argument inventory; rejected arguments cannot disappear before admission. */
class ReviewedStringListInput
private constructor(
    val formal: ReviewedVarargFormal,
    val artifact: ReviewedStringListArtifact,
    val actualArgumentCount: Int,
    val actualArgumentRanges: List<ExactDeclarationTextRange>,
    val arguments: List<ReviewedStringVarargArgument>,
) {
    val invocation: ValueInvocation
        get() = formal.invocation

    val argumentCount: Int
        get() = arguments.size

    val retainedBytes: Long
        get() =
            STRING_PROOF_BYTES.addBytes(formal.retainedBytes)
                .addBytes(artifact.retainedBytes)
                .addBytes(actualArgumentRanges.size.toLong().multiplyBytes(STRING_RANGE_BYTES))
                .addBytes(arguments.fold(0L) { sum, argument -> sum.addBytes(argument.retainedBytes) })

    companion object {
        fun fromCompiler(
            formal: ReviewedVarargFormal,
            artifact: ReviewedStringListArtifact,
            rawActualArgumentCount: Int,
            actualArgumentRanges: List<ExactDeclarationTextRange>,
            arguments: List<ReviewedStringVarargArgument>,
        ): Refinement<ReviewedStringListInput, ReviewedDistinctKeyFailure> {
            val ranges = actualArgumentRanges.toList()
            val observed = arguments.toList()
            if (
                rawActualArgumentCount < MIN_DIRECT_STRING_ARGUMENTS ||
                    rawActualArgumentCount != ranges.size ||
                    rawActualArgumentCount != observed.size
            )
                return distinctKeyRejected(ReviewedDistinctKeyFailure.ARGUMENT_INVENTORY_MISMATCH)
            if (
                observed.withIndex().any { (index, argument) ->
                    argument.ordinal.value != index || argument.range != ranges[index]
                }
            )
                return distinctKeyRejected(ReviewedDistinctKeyFailure.ARGUMENT_INVENTORY_MISMATCH)
            if (observed.any { it.formal != formal })
                return distinctKeyRejected(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
            if (ranges.zipWithNext().any { (first, second) -> first.endExclusive > second.startInclusive })
                return distinctKeyRejected(ReviewedDistinctKeyFailure.ARGUMENT_RANGE_MISMATCH)
            return Refinement.Refined(
                ReviewedStringListInput(
                    formal,
                    artifact,
                    rawActualArgumentCount,
                    Collections.unmodifiableList(ranges),
                    Collections.unmodifiableList(observed),
                )
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedStringListInput &&
            formal == other.formal &&
            artifact == other.artifact &&
            actualArgumentCount == other.actualArgumentCount &&
            actualArgumentRanges == other.actualArgumentRanges &&
            arguments == other.arguments

    override fun hashCode(): Int =
        listOf(formal, artifact, actualArgumentCount, actualArgumentRanges, arguments).hashCode()
}

internal fun distinctKeyRejected(cause: ReviewedDistinctKeyFailure) = Refinement.Rejected(cause)

internal const val STRING_PROOF_BYTES = 2048L
private const val STRING_RANGE_BYTES = 32L
private const val MIN_DIRECT_STRING_ARGUMENTS = 2
