package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Publication keeps row identity and all detached coverage observations in the canonical evidence envelope. */
internal fun QueryReferenceAuthority.resultEnvelope(
    lease: SemanticReadAuthority,
    presented: PresentedQueryRows,
    evidence: QueryProjectedEvidence,
    presentationWindow: QueryRetainedPresentationWindow?,
    presentationOrigin: QueryKnownMinimum?,
): EvidenceEnvelope<QueryRunResult> =
    EvidenceEnvelope(
        CanonicalOperation.QUERY_RUN.id,
        lease.evidenceBasis(),
        QueryRunResult(
            items = presented.items,
            failures = evidence.failures,
            omissions = evidence.omissions,
            walkObservations = evidence.walks,
            referenceObservations = evidence.references,
            discoveryObservations = evidence.discoveries,
            retention = presented.retention,
            nextCursor = presentationWindow?.nextCursor,
            referenceAcquisitions = readAcquisitions(),
            presentationOrigin = presentationOrigin,
            presentationWindow = presentationWindow,
        ),
    )
