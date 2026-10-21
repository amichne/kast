package io.github.amichne.kast.protocol.contract

/** Observation counters remain encoded while the exact qualified call identity remains stable. */
fun BoundedProtocolList<ImpactCallFrameDocument>.hasSameCallStackIdentity(
    other: BoundedProtocolList<ImpactCallFrameDocument>
): Boolean = values.hasSameFrames(other.values)

internal fun List<ImpactCallFrameDocument>.hasSameFrames(other: List<ImpactCallFrameDocument>): Boolean =
    size == other.size && zip(other).all { (first, second) -> first.hasSameFrameIdentity(second) }

internal fun ImpactCallFrameDocument.hasSameFrameIdentity(other: ImpactCallFrameDocument): Boolean =
    enclosing == other.enclosing &&
        invocation == other.invocation &&
        callee == other.callee &&
        target.hasSameTargetIdentity(other.target)

fun ImpactValueSiteReferenceDocument.hasSameValueIdentity(other: ImpactValueSiteReferenceDocument): Boolean =
    enclosing == other.enclosing &&
        range == other.range &&
        role == other.role &&
        executionOwner == other.executionOwner &&
        valuePath == other.valuePath &&
        callStack.hasSameCallStackIdentity(other.callStack)

internal fun ImpactBoundaryPositionDocument.hasSamePositionIdentity(other: ImpactBoundaryPositionDocument): Boolean =
    site.hasSameValueIdentity(other.site) && kind == other.kind && contract == other.contract && slot == other.slot

internal fun ImpactBoundaryRuleDocument.Continuation.hasSameContinuationIdentity(
    other: ImpactBoundaryRuleDocument.Continuation
): Boolean =
    id == other.id &&
        source.hasSamePositionIdentity(other.source) &&
        target.hasSamePositionIdentity(other.target) &&
        assumptions == other.assumptions

private fun ImpactCallTargetDocument.hasSameTargetIdentity(other: ImpactCallTargetDocument): Boolean =
    when (this) {
        ImpactCallTargetDocument.Direct -> other == ImpactCallTargetDocument.Direct
        is ImpactCallTargetDocument.Implementation ->
            other is ImpactCallTargetDocument.Implementation &&
                relation == other.relation &&
                ownership == other.ownership &&
                dispatch.hasSameDispatchIdentity(other.dispatch)
    }

private fun ImpactImplementationDispatchDocument.hasSameDispatchIdentity(
    other: ImpactImplementationDispatchDocument
): Boolean =
    subjectFingerprint == other.subjectFingerprint &&
        question.subject == other.question.subject &&
        question.meaning == other.question.meaning &&
        question.requestedDomain == other.question.requestedDomain &&
        question.domain == other.question.domain &&
        question.fingerprint == other.question.fingerprint &&
        question.position.samePosition(other.question.position) &&
        facts == other.facts &&
        scopeExclusions == other.scopeExclusions &&
        omissions == other.omissions &&
        coverage.hasSameCoverageIdentity(other.coverage)

private fun ImpactFlowReadPositionDocument.samePosition(other: ImpactFlowReadPositionDocument): Boolean =
    when (this) {
        ImpactFlowReadPositionDocument.Start -> other == ImpactFlowReadPositionDocument.Start
        is ImpactFlowReadPositionDocument.Resume ->
            other is ImpactFlowReadPositionDocument.Resume && fingerprint == other.fingerprint
    }

private fun ImpactImplementationCoverageDocument.hasSameCoverageIdentity(
    other: ImpactImplementationCoverageDocument
): Boolean =
    when (this) {
        is ImpactImplementationCoverageDocument.Exhausted ->
            other is ImpactImplementationCoverageDocument.Exhausted && exactCount == other.exactCount
        is ImpactImplementationCoverageDocument.TerminalIncomplete ->
            other is ImpactImplementationCoverageDocument.TerminalIncomplete &&
                knownMinimum == other.knownMinimum &&
                limitations == other.limitations
        is ImpactImplementationCoverageDocument.Resumable ->
            other is ImpactImplementationCoverageDocument.Resumable &&
                knownMinimum == other.knownMinimum &&
                limitations == other.limitations &&
                continuation.fingerprint == other.continuation.fingerprint
    }
