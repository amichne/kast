package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Exact declaration admission precedes dependency artifact reads. */
class ReviewedLibraryCallable
private constructor(
    val target: CompilerGroundedSymbolEvidence,
    val position: ValueArgumentPosition,
    val operation: ReviewedLibraryOperation,
) {
    companion object {
        fun admit(
            target: CompilerGroundedSymbolEvidence,
            position: ValueArgumentPosition,
        ): Refinement<ReviewedLibraryCallable, ReviewedCallbackValueFailure> {
            val signature =
                target.signature as? CanonicalCompilerSignature.Function
                    ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            val file =
                target.file as? SymbolDiscoveryFileIdentity.External
                    ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            val operation =
                when (val value = signature.reviewedOperation(file)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val parameters =
                when (val value = operation.callbackParameterTypes()) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            if (operation == ReviewedLibraryOperation.WITH && position.value != 1)
                return Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH)
            if (position.value !in parameters.indices || signature.valueParameters.map { it.value } != parameters)
                return Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH)
            return Refinement.Refined(ReviewedLibraryCallable(target, position, operation))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedLibraryCallable && target == other.target && position == other.position

    override fun hashCode(): Int = 31 * target.hashCode() + position.hashCode()
}

private fun CanonicalCompilerSignature.Function.matchesReviewedIterableMap(
    receiver: CanonicalCompilerReceiver.Present,
    file: SymbolDiscoveryFileIdentity.External,
): Boolean =
    qualifiedIdentity.value == "kotlin.collections.map" &&
        receiver.type.value == "kotlin/collections/Iterable<T>" &&
        contextReceivers.isEmpty() &&
        typeParameterCount.value == MAP_TYPE_PARAMETERS &&
        file.url.value.endsWith("!/kotlin/collections/CollectionsKt.class")

private fun CanonicalCompilerSignature.Function.matchesReviewedScope(
    receiver: CanonicalCompilerReceiver.Present,
    file: SymbolDiscoveryFileIdentity.External,
    name: String,
    parameters: Int,
): Boolean =
    qualifiedIdentity.value == "kotlin.$name" &&
        receiver.type.value == "T" &&
        contextReceivers.isEmpty() &&
        typeParameterCount.value == parameters &&
        file.url.value.endsWith("!/kotlin/StandardKt.class")

private const val MAP_TYPE_PARAMETERS = 2

private fun CanonicalCompilerSignature.Function.matchesReviewedCollection(
    receiver: CanonicalCompilerReceiver.Present,
    file: SymbolDiscoveryFileIdentity.External,
    name: String,
    parameters: Int,
): Boolean =
    qualifiedIdentity.value == "kotlin.collections.$name" &&
        receiver.type.value == "kotlin/collections/Iterable<T>" &&
        contextReceivers.isEmpty() &&
        typeParameterCount.value == parameters &&
        file.url.value.endsWith("!/kotlin/collections/CollectionsKt.class")

private fun CanonicalCompilerSignature.Function.matchesReviewedMapValues(
    receiver: CanonicalCompilerReceiver.Present,
    file: SymbolDiscoveryFileIdentity.External,
): Boolean =
    qualifiedIdentity.value == "kotlin.collections.mapValues" &&
        receiver.type.value == "kotlin/collections/Map<outK,V>" &&
        contextReceivers.isEmpty() &&
        typeParameterCount.value == MAP_VALUES_TYPE_PARAMETERS &&
        file.url.value.endsWith("!/kotlin/collections/MapsKt.class")

private const val MAP_VALUES_TYPE_PARAMETERS = 3

private fun CanonicalCompilerSignature.Function.reviewedOperation(
    file: SymbolDiscoveryFileIdentity.External
): Refinement<ReviewedLibraryOperation, ReviewedCallbackValueFailure> {
    val receiver = this.receiver
    if (receiver == CanonicalCompilerReceiver.Absent) {
        val operation =
            when {
                matchesReviewedAbsentScope(file, "run", 1) -> ReviewedLibraryOperation.RUN_TOP_LEVEL
                matchesReviewedAbsentScope(file, "with", 2) -> ReviewedLibraryOperation.WITH
                else -> return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            }
        return Refinement.Refined(operation)
    }
    return when (receiver) {
        CanonicalCompilerReceiver.Absent -> Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
        is CanonicalCompilerReceiver.Present -> reviewedPresentOperation(receiver, file)
    }
}

private fun CanonicalCompilerSignature.Function.reviewedPresentOperation(
    receiver: CanonicalCompilerReceiver.Present,
    file: SymbolDiscoveryFileIdentity.External,
): Refinement<ReviewedLibraryOperation, ReviewedCallbackValueFailure> {
    val operation =
        when {
            this.matchesReviewedMapValues(receiver, file) -> ReviewedLibraryOperation.MAP_VALUES
            this.matchesReviewedIterableMap(receiver, file) -> ReviewedLibraryOperation.ITERABLE_MAP
            this.matchesReviewedCollection(receiver, file, "groupBy", 2) -> ReviewedLibraryOperation.ITERABLE_GROUP_BY
            this.matchesReviewedCollection(receiver, file, "groupBy", 3) ->
                ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM
            this.matchesReviewedCollection(receiver, file, "filter", 1) -> ReviewedLibraryOperation.ITERABLE_FILTER
            this.matchesReviewedCollection(receiver, file, "mapNotNull", 2) ->
                ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL
            this.matchesReviewedCollection(receiver, file, "sortedBy", 2) -> ReviewedLibraryOperation.ITERABLE_SORTED_BY
            this.matchesReviewedCollection(receiver, file, "associate", 3) ->
                ReviewedLibraryOperation.ITERABLE_ASSOCIATE
            this.matchesReviewedScope(receiver, file, "run", 2) -> ReviewedLibraryOperation.RUN_RECEIVER
            this.matchesReviewedScope(receiver, file, "apply", 1) -> ReviewedLibraryOperation.APPLY
            this.matchesReviewedScope(receiver, file, "let", 2) -> ReviewedLibraryOperation.LET
            this.matchesReviewedScope(receiver, file, "also", 1) -> ReviewedLibraryOperation.ALSO
            else -> return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
        }
    return Refinement.Refined(operation)
}

private fun CanonicalCompilerSignature.Function.matchesReviewedAbsentScope(
    file: SymbolDiscoveryFileIdentity.External,
    name: String,
    parameters: Int,
): Boolean =
    qualifiedIdentity.value == "kotlin.$name" &&
        contextReceivers.isEmpty() &&
        typeParameterCount.value == parameters &&
        file.url.value.endsWith("!/kotlin/StandardKt.class")

private fun ReviewedLibraryOperation.callbackParameterTypes(): Refinement<List<String>, ReviewedCallbackValueFailure> {
    val parameters =
        when (this) {
            ReviewedLibraryOperation.RUN_TOP_LEVEL -> listOf("()->R")
            ReviewedLibraryOperation.RUN_RECEIVER -> listOf("T.()->R")
            ReviewedLibraryOperation.WITH -> listOf("T", "T.()->R")
            ReviewedLibraryOperation.APPLY -> listOf("T.()->kotlin/Unit")
            ReviewedLibraryOperation.ITERABLE_MAP,
            ReviewedLibraryOperation.LET -> listOf("(T)->R")
            ReviewedLibraryOperation.ALSO -> listOf("(T)->kotlin/Unit")
            ReviewedLibraryOperation.MAP_VALUES -> listOf("(kotlin/collections/Map.Entry<K,V>)->R")
            ReviewedLibraryOperation.ITERABLE_FILTER -> listOf("(T)->kotlin/Boolean")
            ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL,
            ReviewedLibraryOperation.ITERABLE_SORTED_BY -> listOf("(T)->R?")
            ReviewedLibraryOperation.ITERABLE_GROUP_BY -> listOf("(T)->K")
            ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM -> listOf("(T)->K", "(T)->V")
            ReviewedLibraryOperation.ITERABLE_ASSOCIATE -> listOf("(T)->kotlin/Pair<K,V>")
            ReviewedLibraryOperation.MAP_ENTRY_KEY,
            ReviewedLibraryOperation.MAP_ENTRY_VALUE,
            ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
            ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2,
            ReviewedLibraryOperation.TO,
            ReviewedLibraryOperation.PAIR_CONSTRUCTOR,
            ReviewedLibraryOperation.PAIR_FIRST,
            ReviewedLibraryOperation.PAIR_SECOND ->
                return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
        }
    return Refinement.Refined(parameters)
}
