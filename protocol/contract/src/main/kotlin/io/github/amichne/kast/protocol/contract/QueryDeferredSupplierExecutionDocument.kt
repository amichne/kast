package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

@Serializable
enum class QueryCallbackAccessorKindDocument {
    GETTER,
    SETTER,
}

sealed interface QueryDeferredSupplierExecutionDocument {
    data object Declaration : QueryDeferredSupplierExecutionDocument

    data class Accessor(val kind: QueryCallbackAccessorKindDocument, val occurrence: RelationOccurrenceDocument) :
        QueryDeferredSupplierExecutionDocument

    data class Initializer(val occurrence: RelationOccurrenceDocument) : QueryDeferredSupplierExecutionDocument

    data class Invoked(val steps: BoundedProtocolList<QueryDeferredDirectInvocationDocument>) :
        QueryDeferredSupplierExecutionDocument
}

data class QueryDeferredDirectInvocationDocument(
    val binding: QueryCallbackBindingDocument.Direct,
    val body: QueryCallbackBodyDocument.Anonymous,
)

internal fun QueryCallbackBindingDocument.DeferredDependency.admitExecution():
    Refinement<Unit, QueryCallbackDocumentFailure> {
    if (!owner.validDeclaration() || !owner.declaration.contains(occurrence)) return executionRejected()
    return when (val proof = execution) {
        QueryDeferredSupplierExecutionDocument.Declaration -> admitDeclaration()
        is QueryDeferredSupplierExecutionDocument.Accessor ->
            if (owner.compilerTarget.compilerEvidence.signature !is CompilerSignatureDocument.Property)
                executionRejected()
            else admitRegion(proof.occurrence)
        is QueryDeferredSupplierExecutionDocument.Initializer ->
            if (owner.compilerTarget.compilerEvidence.signature !is CompilerSignatureDocument.ClassLike)
                executionRejected()
            else admitRegion(proof.occurrence)
        is QueryDeferredSupplierExecutionDocument.Invoked -> admitDirectRoute(proof)
    }
}

private fun QueryCallbackBindingDocument.DeferredDependency.admitDeclaration():
    Refinement<Unit, QueryCallbackDocumentFailure> =
    when (owner.compilerTarget.compilerEvidence.signature) {
        is CompilerCallableSignatureDocument,
        is CompilerSignatureDocument.Property -> Refinement.Refined(Unit)
        is CompilerSignatureDocument.ClassLike,
        is CompilerSignatureDocument.LocalProperty,
        is CompilerSignatureDocument.TypeAlias,
        is CompilerSignatureDocument.AnonymousObject -> executionRejected()
    }

private fun QueryCallbackBindingDocument.DeferredDependency.admitRegion(
    region: RelationOccurrenceDocument
): Refinement<Unit, QueryCallbackDocumentFailure> =
    if (owner.declaration.contains(region) && region.contains(occurrence)) Refinement.Refined(Unit)
    else executionRejected()

private fun QueryCallbackBindingDocument.DeferredDependency.admitDirectRoute(
    proof: QueryDeferredSupplierExecutionDocument.Invoked
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (!owner.validCallable() || proof.steps.values.isEmpty()) return executionRejected()
    var expectedOwner: QueryCallbackBodyDocument = QueryCallbackBodyDocument.Named(owner)
    if (proof.steps.values.map { it.body }.distinct().size != proof.steps.values.size) return executionRejected()
    for (step in proof.steps.values) {
        when (val admitted = step.admitStep(expectedOwner, basis)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        expectedOwner = step.body
    }
    return if (proof.steps.values.last().body.occurrence.contains(occurrence)) Refinement.Refined(Unit)
    else executionRejected()
}

private fun QueryDeferredDirectInvocationDocument.admitStep(
    expectedOwner: QueryCallbackBodyDocument,
    basis: ImpactSemanticBasisDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (binding.basis != basis || binding.owner != expectedOwner || !body.validBody()) return executionRejected()
    val parent =
        when (val admitted = binding.owner.admitOwner()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return if (parent.contains(binding.occurrence) && binding.occurrence.contains(body.occurrence))
        Refinement.Refined(Unit)
    else executionRejected()
}

private fun executionRejected(): Refinement.Rejected<QueryCallbackDocumentFailure> =
    Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
