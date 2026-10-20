package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueConstructorStorage
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.psi.KtPrimaryConstructor

/** Primary-constructor storage keeps the resolved property identity and the mapped declared parameter position. */
internal fun nativeValueConstructorArgument(
    source: ValueSite,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val argument = source.role as? ValueRole.Argument ?: return constructorRejected()
    val storage =
        when (val value = nativeConstructorStorage(argument, scope, projection)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val path =
        when (val value = source.valuePath.prepend(ValuePathSegment.Field(storage.field))) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
        }
    val target =
        when (val value = argument.call.resultSite().withState(path, source.callStack)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return constructorRejected()
        }
    return sourceTransfer(
        source = source,
        target = target,
        kind = ValueTransferKind.CONSTRUCTOR_FIELD,
        operation = ValueSourceOperation.ConstructField(argument.call, storage),
    )
}

private fun nativeConstructorStorage(
    argument: ValueRole.Argument,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueConstructorStorage, ValueFlowUnsupportedCause> {
    val constructor =
        when (val native = projection.subject(scope, argument.call.callable)) {
            is IntellijRelationSubjectLookup.Found -> native.declaration as? KtPrimaryConstructor
            is IntellijRelationSubjectLookup.Rejected -> null
        } ?: return constructorRejected()
    val parameter = constructor.valueParameters.getOrNull(argument.position.value) ?: return constructorRejected()
    val formal =
        when (val value = nativeValueFormalParameter(argument, parameter)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val field =
        when (
            val value =
                nativeValueField(
                    declaration = parameter,
                    enclosing = argument.call.callable,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    return when (val value = ValueConstructorStorage.fromCompiler(formal, field)) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> constructorRejected()
    }
}

private fun constructorRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
