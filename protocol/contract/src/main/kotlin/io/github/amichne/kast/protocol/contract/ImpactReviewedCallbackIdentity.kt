package io.github.amichne.kast.protocol.contract

internal fun ImpactCallTargetDocument.ReviewedCallback.hasSameReviewedIdentity(
    other: ImpactCallTargetDocument.ReviewedCallback
): Boolean =
    binding.hasSameReviewedBinding(other.binding) &&
        argument == other.argument &&
        formal == other.formal &&
        formalCallable == other.formalCallable &&
        formalExecution.hasSameModelExecution(other.formalExecution)

internal fun ImpactReviewedLibraryBindingDocument.hasSameReviewedBinding(
    other: ImpactReviewedLibraryBindingDocument
): Boolean =
    enclosing == other.enclosing &&
        invocation == other.invocation &&
        callable == other.callable &&
        execution.hasSameModelExecution(other.execution) &&
        model == other.model &&
        artifact == other.artifact

private fun ImpactModelExecutionDocument.hasSameModelExecution(other: ImpactModelExecutionDocument): Boolean =
    enclosing == other.enclosing &&
        when (this) {
            is ImpactModelExecutionDocument.Declaration -> other is ImpactModelExecutionDocument.Declaration
            is ImpactModelExecutionDocument.Anonymous ->
                other is ImpactModelExecutionDocument.Anonymous && body.sameCallbackProof(other.body)
        }
