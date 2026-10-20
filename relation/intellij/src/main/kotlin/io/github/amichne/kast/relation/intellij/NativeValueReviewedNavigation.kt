@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicateSubject
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression

internal sealed interface NativeValueReviewedNavigation {
    data object Direct : NativeValueReviewedNavigation

    data class Safe(val checked: RelationOccurrence) : NativeValueReviewedNavigation
}

internal data class NativeValueReviewedReceiver(
    val binding: ReviewedLibraryBinding,
    val navigation: NativeValueReviewedNavigation,
)

/** Only the compiler safe-navigation fact establishes this condition. The reviewed model supplies no NonNull fact. */
internal fun nativeReviewedNavigation(
    model: ReviewedValueModel,
    enclosing: RelationEndpoint,
    expression: KtExpression,
    qualified: KtQualifiedExpression,
    receiver: KaReceiverValue?,
): Refinement<NativeValueReviewedNavigation, NativeValueModelFailure> {
    when (model) {
        ReviewedValueModel.SingleElementListOf -> return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
        ReviewedValueModel.FirstOrNull -> return Refinement.Refined(NativeValueReviewedNavigation.Direct)
        ReviewedValueModel.Let -> Unit
    }
    val explicit =
        receiver as? KaExplicitReceiverValue ?: return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
    if (explicit.expression.originalElement != expression.originalElement)
        return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
    return when {
        qualified is KtSafeQualifiedExpression && explicit.isSafeNavigation ->
            Refinement.Refined(
                NativeValueReviewedNavigation.Safe(
                    RelationOccurrence.fromSource(enclosing.file, sourceValueRange(expression))
                )
            )
        qualified !is KtSafeQualifiedExpression && !explicit.isSafeNavigation ->
            Refinement.Refined(NativeValueReviewedNavigation.Direct)
        else -> artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
    }
}

internal fun nativeSafeReceiverArrival(
    source: ValueSite,
    role: ValueRole.Receiver,
    checked: RelationOccurrence,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    if (source.role != ValueRole.LocalRead || checked.file != source.enclosing.file || checked.range != source.range)
        return safeReceiverRejected()
    val added =
        when (val admitted = safeNonNullPredicates(source)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val predicates =
        when (val admitted = source.predicates.plus(added.entries)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.nativePredicateCause())
        }
    val target =
        when (
            val admitted =
                ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = checked.range,
                    role = role,
                    executionOwner = source.executionOwner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                    predicates = predicates,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return safeReceiverRejected()
        }
    val evidence =
        when (val admitted = ValueTransferEvidence.Guarded.fromCompiler(ValueTransferEvidence.Direct, added)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.nativePredicateCause())
        }
    return when (
        val admitted =
            ValueTransfer.fromCompiler(
                source = source,
                target = target,
                kind = ValueTransferKind.RECEIVER,
                evidence = evidence,
            )
    ) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> safeReceiverRejected()
    }
}

private fun safeNonNullPredicates(source: ValueSite): Refinement<ValuePredicates, ValueFlowUnsupportedCause> {
    val whole =
        when (val admitted = source.withState(valuePath = ValueAccessPath.Whole)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return safeReceiverRejected()
        }
    val predicate = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(whole))
    return when (val admitted = ValuePredicates.fromCompiler(listOf(predicate))) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(admitted.failure.nativePredicateCause())
    }
}

private fun safeReceiverRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
