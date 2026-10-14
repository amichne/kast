package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackAccessorKindDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryDeferredDependencyModelDocument
import io.github.amichne.kast.protocol.contract.QueryDeferredDirectInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryDeferredSupplierExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryKotlinLazyBinaryDocument
import io.github.amichne.kast.relation.contract.CallbackAccessorKind
import io.github.amichne.kast.relation.contract.CallbackDeferredExecution

internal fun CallbackProjection.deferredDependency(
    value: io.github.amichne.kast.relation.contract.CallbackDeferredDependency
): QueryCallbackBindingDocument? {
    val classes = value.binary.classes
    return QueryCallbackBindingDocument.DeferredDependency(
        value.basis.impactDocument().callbackValue() ?: return null,
        occurrence(value.occurrence) ?: return null,
        callable(value.owner.evidence) ?: return null,
        dependencyTarget(value.target) ?: return null,
        ProtocolOffset.parse(value.position.value).callbackValue() ?: return null,
        projectLazyBinary(classes) ?: return null,
        deferredExecution(value.owner.execution) ?: return null,
    )
}

private fun projectLazyBinary(
    classes: io.github.amichne.kast.relation.contract.KotlinLazyClassDigests
): QueryKotlinLazyBinaryDocument? =
    QueryKotlinLazyBinaryDocument(
        QueryDeferredDependencyModelDocument.KOTLIN_LAZY_JVM_V1,
        ProtocolText.parse(classes.facade.value).callbackValue() ?: return null,
        ProtocolText.parse(classes.factory.value).callbackValue() ?: return null,
        ProtocolText.parse(classes.synchronized.value).callbackValue() ?: return null,
        ProtocolText.parse(classes.publication.value).callbackValue() ?: return null,
        ProtocolText.parse(classes.unsafe.value).callbackValue() ?: return null,
        ProtocolText.parse(classes.modeDispatch.value).callbackValue() ?: return null,
    )

private fun CallbackProjection.deferredExecution(
    value: CallbackDeferredExecution
): QueryDeferredSupplierExecutionDocument? =
    when (value) {
        CallbackDeferredExecution.Declaration -> QueryDeferredSupplierExecutionDocument.Declaration
        is CallbackDeferredExecution.Accessor ->
            QueryDeferredSupplierExecutionDocument.Accessor(
                when (value.kind) {
                    CallbackAccessorKind.GETTER -> QueryCallbackAccessorKindDocument.GETTER
                    CallbackAccessorKind.SETTER -> QueryCallbackAccessorKindDocument.SETTER
                },
                occurrence(value.occurrence) ?: return null,
            )
        is CallbackDeferredExecution.Initializer ->
            QueryDeferredSupplierExecutionDocument.Initializer(occurrence(value.occurrence) ?: return null)
        is CallbackDeferredExecution.Invoked -> {
            val steps =
                value.route.steps.map { step ->
                    QueryDeferredDirectInvocationDocument(
                        QueryCallbackBindingDocument.Direct(
                            step.binding.basis.impactDocument().callbackValue() ?: return null,
                            occurrence(step.binding.occurrence) ?: return null,
                            body(step.binding.owner) ?: return null,
                        ),
                        body(step.body) as? QueryCallbackBodyDocument.Anonymous ?: return null,
                    )
                }
            QueryDeferredSupplierExecutionDocument.Invoked(
                BoundedProtocolList.create(steps).callbackValue() ?: return null
            )
        }
    }

private fun <V, F> Refinement<V, F>.callbackValue(): V? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
