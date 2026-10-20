package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicateSubject
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.hasSameProofAs
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtThisExpression

/** A current direct return can add its actual caller fact. The original callee facts stay unchanged. */
internal class NativeValueCallerNonNull(
    private val transfer: ValueTransfer,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val permit: () -> Boolean,
) {
    private val source = transfer.source

    fun read(): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
        val operation =
            (transfer.evidence as? ValueSourceTransfer)?.operation as? ValueSourceOperation.Return
                ?: return Refinement.Refined(transfer)
        if (!source.admitsCallerAlias(operation.frame)) return Refinement.Refined(transfer)
        val candidates =
            source.predicates.entries.filterIsInstance<ValuePredicate.NonNull>().filter {
                source.hasCurrentThisContext(it.subject.occurrence)
            }
        if (candidates.isEmpty() || !permit()) return Refinement.Refined(transfer)
        val function =
            when (val restored = projection.subject(scope, source.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> restored.declaration as? KtNamedFunction
                is IntellijRelationSubjectLookup.Rejected -> null
            } ?: return callerAliasRejected()
        for (candidate in candidates) {
            val expression =
                function.exactValueElement(candidate.subject.occurrence.range) as? KtThisExpression ?: continue
            return fromThis(function, expression, operation.frame)
        }
        return Refinement.Refined(transfer)
    }

    private fun fromThis(
        function: KtNamedFunction,
        expression: KtThisExpression,
        frame: ValueCallFrame,
    ): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
        if (valueNativeOwnership(expression, function) != NativeValueOwnership.ADMITTED) return callerAliasRejected()
        if (!permit()) return Refinement.Refined(transfer)
        val kind =
            when (val actual = nativeThisReceiverKind(function, expression, projection.observation)) {
                is Refinement.Refined -> actual.value
                is Refinement.Rejected -> return actual
            }
        if (!permit()) return Refinement.Refined(transfer)
        val receiver =
            when (val actual = nativeCallerReceiver(source, kind, scope, projection)) {
                is Refinement.Refined -> actual.value
                is Refinement.Rejected -> return actual
            }
        if (!receiver.matchesCaller(transfer, frame)) return callerAliasRejected()
        return guardedCallerReturn(transfer, receiver)
    }
}

private fun ValueSite.admitsCallerAlias(frame: ValueCallFrame): Boolean {
    val current = callStack.frames.lastOrNull() ?: return false
    if (role != ValueRole.Return || executionOwner.body != ValueExecutionBody.Declaration) return false
    return frame.hasSameDirectProofAs(current) &&
        executionOwner.hasSameProofAs(frame.callee) &&
        enclosing.hasSameProofAs(frame.callee.enclosing)
}

private fun ValueSite.hasCurrentThisContext(subject: ValueSite): Boolean {
    if (subject.role != ValueRole.LocalRead) return false
    if (subject.valuePath != ValueAccessPath.Whole || subject.predicates != ValuePredicates.Empty) return false
    return subject.enclosing.hasSameProofAs(enclosing) &&
        subject.executionOwner.hasSameProofAs(executionOwner) &&
        subject.callStack.hasSameDirectProofAs(callStack)
}

private fun ValueSite.matchesCaller(transfer: ValueTransfer, frame: ValueCallFrame): Boolean {
    val receiver = role as? ValueRole.Receiver ?: return false
    val actual =
        when (val admitted = ValueCallFrame.direct(receiver.call, frame.callee)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return false
        }
    if (!actual.hasSameDirectProofAs(frame) || valuePath != ValueAccessPath.Whole) return false
    return enclosing.hasSameProofAs(transfer.target.enclosing) &&
        executionOwner.hasSameProofAs(transfer.target.executionOwner) &&
        callStack.hasSameDirectProofAs(transfer.target.callStack)
}

private fun guardedCallerReturn(
    transfer: ValueTransfer,
    receiver: ValueSite,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val predicate = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(receiver))
    val added =
        when (val admitted = ValuePredicates.fromCompiler(listOf(predicate))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.nativePredicateCause())
        }
    val predicates =
        when (val admitted = transfer.source.predicates.plus(added.entries)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.nativePredicateCause())
        }
    val target =
        when (val admitted = transfer.target.withState(predicates = predicates)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return callerAliasRejected()
        }
    val evidence =
        when (val admitted = ValueTransferEvidence.Guarded.fromCompiler(transfer.evidence, added)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.nativePredicateCause())
        }
    return when (val admitted = ValueTransfer.fromCompiler(transfer.source, target, transfer.kind, evidence)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> callerAliasRejected()
    }
}

private fun callerAliasRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
