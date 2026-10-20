package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicateFailure
import io.github.amichne.kast.relation.contract.ValuePredicateSubject
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** A field condition refers to the caller's exact receiver occurrence and original call stack. */
internal fun nativeGuardedField(
    source: ValueSite,
    transfer: ValueTransfer,
    guard: NativeReceiverGuard,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<NativeValueFieldRead, ValueFlowUnsupportedCause> {
    val declaration =
        when (guard) {
            NativeReceiverGuard.Direct -> return Refinement.Refined(NativeValueFieldRead.Transfer(transfer))
            is NativeReceiverGuard.Subtype -> guard.declaration
        }
    val type =
        when (val value = nativeValueCallableEndpoint(declaration, source.enclosing, scope, projection)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val subject =
        when (val value = nativeCallerReceiver(source, scope, projection)) {
            is Refinement.Refined -> ValuePredicateSubject.fromCompiler(value.value)
            is Refinement.Rejected -> return value
        }
    val predicate =
        when (val value = ValuePredicate.Subtype.fromCompiler(subject, type)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(value.failure.nativePredicateCause())
        }
    val added =
        when (val value = ValuePredicates.fromCompiler(listOf(predicate))) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(value.failure.nativePredicateCause())
        }
    val predicates =
        when (val value = source.predicates.plus(added.entries)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(value.failure.nativePredicateCause())
        }
    val target =
        when (val value = transfer.target.withState(predicates = predicates)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return rejectedGuard()
        }
    val evidence =
        when (val value = ValueTransferEvidence.Guarded.fromCompiler(transfer.evidence, added)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(value.failure.nativePredicateCause())
        }
    return when (val value = ValueTransfer.fromCompiler(source, target, transfer.kind, evidence)) {
        is Refinement.Refined -> Refinement.Refined(NativeValueFieldRead.Transfer(value.value))
        is Refinement.Rejected -> rejectedGuard()
    }
}

private fun nativeCallerReceiver(
    source: ValueSite,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueSite, ValueFlowUnsupportedCause> {
    val position =
        (source.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Receiver ?: return rejectedGuard()
    return nativeCallerReceiver(source, position.kind, scope, projection)
}

internal fun nativeCallerReceiver(
    source: ValueSite,
    kind: ValueReceiverKind,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueSite, ValueFlowUnsupportedCause> {
    val frame = source.callStack.frames.lastOrNull() ?: return rejectedGuard()
    if (frame.callee != source.executionOwner) return rejectedGuard()
    val call = frame.invocation
    val host =
        when (val value = projection.subject(scope, call.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration
            is IntellijRelationSubjectLookup.Rejected -> return rejectedGuard()
        }
    val expression = host.exactValueElement(call.range) ?: return rejectedGuard()
    val role =
        when (
            val value =
                nativeCallerReceiverRole(
                    call = call,
                    kind = kind,
                    expression = expression,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    if (role.call != call || role.kind != kind) return rejectedGuard()
    val stack =
        when (val value = source.callStack.exit(frame)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return rejectedGuard()
        }
    return when (
        val value =
            ValueSite.fromCompiler(
                enclosing = call.enclosing,
                range =
                    sourceValueRange(
                        if (expression is KtQualifiedExpression) expression.receiverExpression else expression
                    ),
                role = role,
                executionOwner = call.executionOwner,
                valuePath = ValueAccessPath.Whole,
                callStack = stack,
                predicates = source.predicates,
            )
    ) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> rejectedGuard()
    }
}

private fun nativeCallerReceiverRole(
    call: ValueInvocation,
    kind: ValueReceiverKind,
    expression: KtExpression,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueRole.Receiver, ValueFlowUnsupportedCause> {
    return if (expression is KtQualifiedExpression)
        nativeValueReceiver(expression.receiverExpression, expression, scope, projection, call.executionOwner)
    else
        nativeImplicitCallerReceiver(
            call = call,
            kind = kind,
            expression = expression as? KtCallElement ?: return rejectedGuard(),
            scope = scope,
            projection = projection,
        )
}

private fun nativeImplicitCallerReceiver(
    call: ValueInvocation,
    kind: ValueReceiverKind,
    expression: KtCallElement,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueRole.Receiver, ValueFlowUnsupportedCause> {
    val function =
        when (val value = projection.subject(scope, call.callable)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration as? KtNamedFunction
            is IntellijRelationSubjectLookup.Rejected -> null
        } ?: return rejectedGuard()
    val role = ValueRole.Receiver(call, kind)
    return if (nativeReceiverPosition(role, call.range, expression, function, projection) == kind)
        Refinement.Refined(role)
    else rejectedGuard()
}

internal fun ValuePredicateFailure.nativePredicateCause(): ValueFlowUnsupportedCause =
    when (this) {
        ValuePredicateFailure.CAPACITY_EXCEEDED -> ValueFlowUnsupportedCause.PREDICATE_LIMIT_REACHED
        ValuePredicateFailure.BASIS_MISMATCH,
        ValuePredicateFailure.NOT_CLASS,
        ValuePredicateFailure.NESTED_GUARD -> ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE
    }

private fun rejectedGuard() = Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
