package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingViewDocument
import io.github.amichne.kast.protocol.contract.ImpactClosureDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.query.contract.QueryImpactClosure
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactRequiredObligation
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.accountingStatus

/** The immutable ledger is the sole authority for original counts, closure, seeds and reviewed model references. */
internal fun QueryRows.impactAccountingDocument(): ImpactProjected<ImpactAccountingDocument> =
    when (this) {
        is QueryRows.Symbols,
        is QueryRows.Occurrences,
        is QueryRows.Bindings -> Refinement.Refined(ImpactAccountingDocument.NotApplicable)
        is QueryRows.ValuePaths ->
            count(values.size).impactThen { pageCount ->
                when (val accounting = accounting) {
                    QueryValuePathAccounting.EvidenceOnly ->
                        Refinement.Refined(ImpactAccountingDocument.EvidenceOnly(pageCount))
                    is QueryValuePathAccounting.Investigated ->
                        accountingStatus.impactDocument().impactThen { status ->
                            accounting.ledger.impactDocument(pageCount, status, ImpactAccountingViewDocument.Paths)
                        }
                }
            }
        is QueryRows.ImpactWitness ->
            count(view.firstOrdinal.value)
                .impactZip(count(view.nextOrdinal.value))
                .impactZip(count(view.sectionCount.value))
                .impactThen { (bounds, total) ->
                    view.ledger.closure.impactDocument().impactThen { closure ->
                        count(0).impactThen { zero ->
                            view.ledger.impactDocument(
                                zero,
                                ImpactAccountingStatusDocument.SelectedSubset(closure),
                                ImpactAccountingViewDocument.Witness(
                                    view.section.witnessDocument(),
                                    bounds.first,
                                    bounds.second,
                                    total,
                                ),
                            )
                        }
                    }
                }
    }

private fun QueryImpactLedger.impactDocument(
    pageCount: QueryDiscoveryCountDocument,
    status: ImpactAccountingStatusDocument,
    view: ImpactAccountingViewDocument,
): ImpactProjected<ImpactAccountingDocument> =
    domain.impactRequestedBoundary().impactThen { requestedDomain ->
        seeds
            .impactEach { it.impactDocument() }
            .impactThen { seeds ->
                representationModels
                    .impactEach { it.reference.impactDocument() }
                    .impactThen { representations ->
                        boundaryModels
                            .impactEach { it.reference.impactDocument() }
                            .impactThen { boundaries ->
                                count(readRejections.size)
                                    .impactZip(count(observations.size))
                                    .impactZip(count(paths.size))
                                    .impactMap { (counts, paths) ->
                                        ImpactAccountingDocument.Investigated(
                                            seeds,
                                            requestedDomain,
                                            when (semantics) {
                                                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1 ->
                                                    ImpactFlowSemanticsDocument.KOTLIN_FORWARD_V1
                                            },
                                            representations,
                                            boundaries,
                                            counts.first,
                                            counts.second,
                                            paths,
                                            pageCount,
                                            status,
                                            view,
                                        )
                                    }
                            }
                    }
            }
    }

private fun QueryValuePathAccountingStatus.impactDocument(): ImpactProjected<ImpactAccountingStatusDocument> =
    when (this) {
        QueryValuePathAccountingStatus.Conserved -> Refinement.Refined(ImpactAccountingStatusDocument.Conserved)
        is QueryValuePathAccountingStatus.Unresolved ->
            required.impactRequirements().impactMap(ImpactAccountingStatusDocument::Unresolved)
        is QueryValuePathAccountingStatus.SelectedSubset ->
            originalClosure.impactDocument().impactMap(ImpactAccountingStatusDocument::SelectedSubset)
        QueryValuePathAccountingStatus.EvidenceOnly ->
            Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    }

private fun QueryImpactClosure.impactDocument(): ImpactProjected<ImpactClosureDocument> =
    when (this) {
        QueryImpactClosure.Discharged -> Refinement.Refined(ImpactClosureDocument.Discharged)
        is QueryImpactClosure.Unresolved -> required.impactRequirements().impactMap(ImpactClosureDocument::Unresolved)
    }

private fun Set<QueryImpactRequiredObligation>.impactRequirements():
    ImpactProjected<BoundedProtocolList<ImpactRequiredObligationDocument>> = sortedBy {
    it.ordinal
}
    .map {
        when (it) {
            QueryImpactRequiredObligation.PRODUCER_IDENTITY -> ImpactRequiredObligationDocument.PRODUCER_IDENTITY
            QueryImpactRequiredObligation.EXECUTION_BOUNDARY -> ImpactRequiredObligationDocument.EXECUTION_BOUNDARY
            QueryImpactRequiredObligation.NATIVE_FLOW -> ImpactRequiredObligationDocument.NATIVE_FLOW
            QueryImpactRequiredObligation.BOUNDARY -> ImpactRequiredObligationDocument.BOUNDARY
            QueryImpactRequiredObligation.REPRESENTATION_STATE -> ImpactRequiredObligationDocument.REPRESENTATION_STATE
        }
    }
    .impactBounded()

private fun count(raw: Int): ImpactProjected<QueryDiscoveryCountDocument> =
    QueryDiscoveryCountDocument.parse(raw.toLong()).impactFailure(ImpactPathProjectionFailure::Count)
