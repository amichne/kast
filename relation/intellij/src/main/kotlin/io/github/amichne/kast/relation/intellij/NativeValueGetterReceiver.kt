package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocationOperation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import org.jetbrains.kotlin.psi.KtProperty

/** Reacquires the compiler-selected read and every sibling before entering the property's getter execution. */
internal fun IntellijRelationAllowance.nativeGetterReceiverFormal(
    source: ValueSite,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueFormalPosition.Receiver, ValueFlowUnsupportedCause> {
    val role = source.role as? ValueRole.Receiver ?: return getterReceiverRejected()
    if (role.call.operation !is ValueInvocationOperation.Getter || role.kind != ValueReceiverKind.DISPATCH)
        return getterReceiverRejected()
    val property =
        when (val value = projection.subject(scope, role.call.callable)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration as? KtProperty
            is IntellijRelationSubjectLookup.Rejected -> null
        } ?: return getterReceiverRejected()
    val host =
        when (val value = projection.subject(scope, role.call.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration
            is IntellijRelationSubjectLookup.Rejected -> return getterReceiverRejected()
        }
    val syntax = host.exactValueElement(role.call.range)?.nativeValueCallSyntax() ?: return getterReceiverRejected()
    if (syntax is NativeValueCallSyntax.PropertyRead) return namedGetterFormal(source, syntax, property, projection)
    if (syntax !is NativeValueCallSyntax.Component || source.range != role.call.range) return getterReceiverRejected()
    return componentGetterFormal(syntax, role, property, scope, projection)
}

private fun IntellijRelationAllowance.componentGetterFormal(
    syntax: NativeValueCallSyntax.Component,
    role: ValueRole.Receiver,
    property: KtProperty,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueFormalPosition.Receiver, ValueFlowUnsupportedCause> {
    val admission =
        when (val value = syntax.admit(role.call.enclosing, scope, projection) { permitDestructuring(scope) }) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    return when (val target = admission.target(syntax.entry)) {
        is Refinement.Rejected -> target
        is Refinement.Refined ->
            if (target.value == NativeComponentTarget.Getter(property))
                Refinement.Refined(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH))
            else getterReceiverRejected()
    }
}

private fun getterReceiverRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)

private fun namedGetterFormal(
    source: ValueSite,
    syntax: NativeValueCallSyntax.PropertyRead,
    property: KtProperty,
    projection: IntellijK2RelationProjection,
): Refinement<ValueFormalPosition.Receiver, ValueFlowUnsupportedCause> {
    if (source.range != sourceValueRange(syntax.expression.receiverExpression)) return getterReceiverRejected()
    return when (val selected = nativeNamedGetter(syntax, projection)) {
        is Refinement.Refined ->
            if (selected.value.originalElement == property.originalElement)
                Refinement.Refined(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH))
            else getterReceiverRejected()
        is Refinement.Rejected -> selected
    }
}
