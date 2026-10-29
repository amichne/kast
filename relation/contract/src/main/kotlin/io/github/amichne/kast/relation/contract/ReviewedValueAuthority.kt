package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

/** Reviewed callback and ordinary call authority share one transfer owner. */
sealed interface ReviewedValueAuthority {
    val enclosing: RelationEndpoint
    val occurrence: RelationOccurrence
    val target: CompilerGroundedSymbolEvidence
    val artifact: ReviewedArtifactEvidence
    val basis: SemanticReadIdentity
    val retainedBytes: Long
}

/** An ordinary selection has no callback formal or callback execution claim. */
class ReviewedOrdinaryLibraryCallable
private constructor(
    val target: CompilerGroundedSymbolEvidence,
    val operation: ReviewedLibraryOperation,
) {
    val sourceEntry: String
        get() =
            when (operation) {
                ReviewedLibraryOperation.MAP_ENTRY_KEY,
                ReviewedLibraryOperation.MAP_ENTRY_VALUE -> "commonMain/kotlin/Collections.kt"
                ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
                ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2 -> "commonMain/kotlin/collections/Maps.kt"
                else -> "commonMain/kotlin/util/Tuples.kt"
            }

    companion object {
        fun admit(
            target: CompilerGroundedSymbolEvidence
        ): Refinement<ReviewedOrdinaryLibraryCallable, ReviewedCallbackValueFailure> {
            val file =
                target.file as? SymbolDiscoveryFileIdentity.External
                    ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            val operation =
                when (val signature = target.signature) {
                    is CanonicalCompilerSignature.Function -> ordinaryFunction(target, file, signature)
                    is CanonicalCompilerSignature.Property -> ordinaryProperty(target, file, signature)
                    else -> Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
                }
            return when (operation) {
                is Refinement.Refined -> Refinement.Refined(ReviewedOrdinaryLibraryCallable(target, operation.value))
                is Refinement.Rejected -> operation
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedOrdinaryLibraryCallable && target == other.target && operation == other.operation

    override fun hashCode(): Int = 31 * target.hashCode() + operation.hashCode()
}

private fun ordinaryFunction(
    target: CompilerGroundedSymbolEvidence,
    file: SymbolDiscoveryFileIdentity.External,
    signature: CanonicalCompilerSignature.Function,
): Refinement<ReviewedLibraryOperation, ReviewedCallbackValueFailure> {
    if (signature.contextReceivers.isNotEmpty()) return ordinaryMismatch()
    val entry = reviewedEntryComponent(target, file, signature)
    if (entry is Refinement.Refined) return entry
    return when {
        target.kind == CompilerSymbolKind.FUNCTION && signature.matchesTupleFactory(file) ->
            Refinement.Refined(ReviewedLibraryOperation.TO)
        target.kind == CompilerSymbolKind.CONSTRUCTOR && signature.matchesPairConstructor(file) ->
            Refinement.Refined(ReviewedLibraryOperation.PAIR_CONSTRUCTOR)
        else -> ordinaryMismatch()
    }
}

private fun CanonicalCompilerSignature.Function.matchesTupleFactory(
    file: SymbolDiscoveryFileIdentity.External
): Boolean =
    qualifiedIdentity.value == "kotlin.to" &&
        (receiver as? CanonicalCompilerReceiver.Present)?.type?.value == "A" &&
        valueParameters.map { it.value } == listOf("B") &&
        typeParameterCount.value == 2 &&
        file.url.value.endsWith("!/kotlin/TuplesKt.class")

private fun CanonicalCompilerSignature.Function.matchesPairConstructor(
    file: SymbolDiscoveryFileIdentity.External
): Boolean =
    qualifiedIdentity.value == "kotlin.Pair.<init>" &&
        receiver == CanonicalCompilerReceiver.Absent &&
        valueParameters.map { it.value } == listOf("A", "B") &&
        typeParameterCount.value == 0 &&
        file.url.value.endsWith("!/kotlin/Pair.class")

private fun ordinaryProperty(
    target: CompilerGroundedSymbolEvidence,
    file: SymbolDiscoveryFileIdentity.External,
    signature: CanonicalCompilerSignature.Property,
): Refinement<ReviewedLibraryOperation, ReviewedCallbackValueFailure> {
    val entry = reviewedEntryProperty(target, file, signature)
    if (entry is Refinement.Refined) return entry
    if (target.kind != CompilerSymbolKind.PROPERTY || !file.url.value.endsWith("!/kotlin/Pair.class"))
        return ordinaryMismatch()
    if (signature.receiver != CanonicalCompilerReceiver.Absent || signature.contextReceivers.isNotEmpty())
        return ordinaryMismatch()
    return when {
        signature.qualifiedIdentity.value == "kotlin.Pair.first" && signature.returnType.value == "A" ->
            Refinement.Refined(ReviewedLibraryOperation.PAIR_FIRST)
        signature.qualifiedIdentity.value == "kotlin.Pair.second" && signature.returnType.value == "B" ->
            Refinement.Refined(ReviewedLibraryOperation.PAIR_SECOND)
        else -> ordinaryMismatch()
    }
}

private fun ordinaryMismatch() = Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)

/** The selected ordinary invocation fixes its source execution owner without an invented callback body. */
class ReviewedCallValueBinding
private constructor(
    val invocation: ValueInvocation,
    val selected: ReviewedOrdinaryLibraryCallable,
    override val artifact: ReviewedArtifactEvidence,
    override val occurrence: RelationOccurrence,
    val entryReadOrder: ReviewedEntryReadOrder = ReviewedEntryReadOrder.Direct,
) : ReviewedValueAuthority {
    override val enclosing
        get() = invocation.enclosing

    override val basis
        get() = invocation.basis

    override val target
        get() = selected.target

    override val retainedBytes: Long
        get() = reviewedCallStorageBytes(invocation).addBytes(entryReadOrder.retainedBytes)

    companion object {
        fun admitDestructuredEntry(
            invocation: ValueInvocation,
            selected: ReviewedOrdinaryLibraryCallable,
            artifact: ReviewedArtifactEvidence,
            declarationRange: ExactDeclarationTextRange,
            preceding: List<ReviewedEntryComponentInvocation>,
        ): Refinement<ReviewedCallValueBinding, ReviewedCallbackValueFailure> {
            val current =
                when (val value = admit(invocation, selected, artifact)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            return when (
                val order = ReviewedEntryReadOrder.Destructured.fromCompiler(current, declarationRange, preceding)
            ) {
                is Refinement.Rejected -> order
                is Refinement.Refined ->
                    Refinement.Refined(
                        ReviewedCallValueBinding(invocation, selected, artifact, current.occurrence, order.value)
                    )
            }
        }

        fun admit(
            invocation: ValueInvocation,
            selected: ReviewedOrdinaryLibraryCallable,
            artifact: ReviewedArtifactEvidence,
        ): Refinement<ReviewedCallValueBinding, ReviewedCallbackValueFailure> {
            if (
                selected.operation != artifact.rule.operation ||
                    invocation.callable.valueIdentity != ValueDeclarationIdentity.fromCompiler(selected.target) ||
                    invocation.signature != selected.target.signature
            )
                return ordinaryMismatch()
            val occurrence =
                when (
                    val value =
                        RelationOccurrence.fromBoundary(
                            invocation.enclosing.file,
                            invocation.range.startInclusive,
                            invocation.range.endExclusive,
                        )
                ) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
                }
            return Refinement.Refined(ReviewedCallValueBinding(invocation, selected, artifact, occurrence))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedCallValueBinding &&
            invocation == other.invocation &&
            selected == other.selected &&
            artifact == other.artifact &&
            entryReadOrder == other.entryReadOrder

    override fun hashCode(): Int = listOf(invocation, selected, artifact, entryReadOrder).hashCode()
}

internal fun reviewedCallStorageBytes(invocation: ValueInvocation): Long =
    8192L
        .addBytes(invocation.enclosing.detachedTextUnits().multiplyBytes(2))
        .addBytes(invocation.callable.detachedTextUnits().multiplyBytes(2))
        .addBytes(valueExecutionStorageBytes(invocation.executionOwner))
