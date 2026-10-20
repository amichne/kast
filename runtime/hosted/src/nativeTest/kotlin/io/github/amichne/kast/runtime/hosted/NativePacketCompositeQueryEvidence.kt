package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.EmptyImpactCallStack
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactPredicateSubjectDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.hasSameCallStackIdentity

/** These functions inspect detached witnesses. They never request or explore a value-flow operation. */
internal fun ImpactPathDocument.compositeSites(): List<ImpactValueSiteReferenceDocument> =
    listOf(producer) +
        steps.values.flatMap { listOf(it.compositeSource(), it.compositeTarget()) } +
        listOfNotNull((terminal as? ImpactPathTerminalDocument.UnresolvedFlow)?.site)

internal fun ImpactPathDocument.compositeContiguous(): Boolean {
    if (steps.values.isEmpty() || steps.values.any { it is ImpactPathStepDocument.ModeledBoundary }) return false
    val sites = steps.values.map { it.compositeSource() to it.compositeTarget() }
    return producer == sites.first().first &&
        sites.zipWithNext().all { (first, second) ->
            first.second.copy(callStack = EmptyImpactCallStack) ==
                second.first.copy(callStack = EmptyImpactCallStack) &&
                first.second.callStack.hasSameCallStackIdentity(second.first.callStack)
        }
}

internal fun ImpactPathStepDocument.compositeSource(): ImpactValueSiteReferenceDocument =
    when (this) {
        is ImpactPathStepDocument.Compiler -> transfer.source
        is ImpactPathStepDocument.ModeledValue -> application.source
        is ImpactPathStepDocument.ModeledRepresentation -> application.source
        is ImpactPathStepDocument.ModeledBoundary -> connection.rule.source.site
    }

internal fun ImpactPathStepDocument.compositeTarget(): ImpactValueSiteReferenceDocument =
    when (this) {
        is ImpactPathStepDocument.Compiler -> transfer.target
        is ImpactPathStepDocument.ModeledValue -> application.target
        is ImpactPathStepDocument.ModeledRepresentation -> application.target
        is ImpactPathStepDocument.ModeledBoundary -> connection.rule.target.site
    }

internal fun ImpactCompilerTransferDocument.compositeOperation(): ImpactSourceOperationDocument? {
    val unguarded = (evidence as? ImpactTransferEvidenceDocument.Guarded)?.delegate ?: evidence
    return (unguarded as? ImpactTransferEvidenceDocument.Source)?.operation
}

internal fun ImpactValueSiteReferenceDocument.compositeSubject() =
    ImpactPredicateSubjectDocument(
        enclosing = enclosing,
        range = range,
        role = role,
        executionOwner = executionOwner,
        valuePath = valuePath,
        callStack = callStack,
    )
