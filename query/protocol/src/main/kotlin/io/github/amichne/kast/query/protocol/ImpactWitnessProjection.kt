package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactNativeObservationTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessItemDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.query.contract.QueryImpactProducerEvidence
import io.github.amichne.kast.query.contract.QueryImpactWitnessEntry
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.ValueFlowTerminal

internal fun ImpactWitnessSectionDocument.witnessSection(): QueryImpactWitnessSection =
    when (this) {
        ImpactWitnessSectionDocument.PRODUCERS -> QueryImpactWitnessSection.PRODUCERS
        ImpactWitnessSectionDocument.MODELS -> QueryImpactWitnessSection.MODELS
        ImpactWitnessSectionDocument.NATIVE_READS -> QueryImpactWitnessSection.NATIVE_READS
        ImpactWitnessSectionDocument.READ_REJECTIONS -> QueryImpactWitnessSection.READ_REJECTIONS
    }

internal fun QueryImpactWitnessSection.witnessDocument(): ImpactWitnessSectionDocument =
    when (this) {
        QueryImpactWitnessSection.PRODUCERS -> ImpactWitnessSectionDocument.PRODUCERS
        QueryImpactWitnessSection.MODELS -> ImpactWitnessSectionDocument.MODELS
        QueryImpactWitnessSection.NATIVE_READS -> ImpactWitnessSectionDocument.NATIVE_READS
        QueryImpactWitnessSection.READ_REJECTIONS -> ImpactWitnessSectionDocument.READ_REJECTIONS
    }

internal fun QueryRows.ImpactWitness.projectWitnessItems(): QueryProjection<QueryResultItemDocument> {
    val result = mutableListOf<QueryResultItemDocument>()
    for (record in values) {
        val projected =
            count(record.ordinal.value).impactZip(record.evidence.impactDocument()).impactMap { (ordinal, witness) ->
                QueryResultItemDocument.ImpactWitness(
                    ImpactWitnessItemDocument(view.section.witnessDocument(), ordinal, witness)
                )
            }
        when (projected) {
            is Refinement.Refined -> result += projected.value
            is Refinement.Rejected -> return QueryProjection.ImpactRejected(projected.failure)
        }
    }
    return QueryProjection.Projected(result)
}

private fun QueryImpactWitnessEntry.impactDocument(): ImpactProjected<ImpactWitnessDocument> =
    when (this) {
        is QueryImpactWitnessEntry.Producer ->
            when (val producer = evidence) {
                is QueryImpactProducerEvidence.Invocation ->
                    producer.site.impactDocument().impactZip(producer.producer.invocation.impactDocument()).impactMap {
                        (site, invocation) ->
                        ImpactWitnessDocument.Producer(site, invocation)
                    }
                is QueryImpactProducerEvidence.SiteOnly ->
                    producer.site.impactDocument().impactMap(ImpactWitnessDocument::ProducerSiteOnly)
            }
        is QueryImpactWitnessEntry.RepresentationModel ->
            rule.reference.impactDocument().impactZip(rule.impactDocument()).impactMap { (reference, rule) ->
                ImpactWitnessDocument.RepresentationModel(reference, rule)
            }
        is QueryImpactWitnessEntry.BoundaryModel ->
            model.reference.impactDocument().impactZip(model.impactDocument()).impactMap { (reference, rule) ->
                ImpactWitnessDocument.BoundaryModel(reference, rule)
            }
        is QueryImpactWitnessEntry.ReadRejected ->
            rejection.impactDocument().impactMap(ImpactWitnessDocument::ReadRejection)
        is QueryImpactWitnessEntry.CompilerTransfer ->
            count(observationOrdinal.value)
                .impactZip(count(transferOrdinal.value))
                .impactZip(transfer.impactDocument())
                .impactMap { (position, transfer) ->
                    ImpactWitnessDocument.CompilerTransfer(position.first, position.second, transfer)
                }
        is QueryImpactWitnessEntry.FlowObligation ->
            count(observationOrdinal.value)
                .impactZip(count(obligationOrdinal.value))
                .impactZip(obligation.site.impactDocument())
                .impactMap { (position, site) ->
                    ImpactWitnessDocument.FlowObligation(
                        position.first,
                        position.second,
                        site,
                        obligation.cause.impactDocument(),
                    )
                }
        is QueryImpactWitnessEntry.NativeRead -> projectNativeRead()
    }

private fun count(raw: Int) = count(raw.toLong())

private fun count(raw: Long): ImpactProjected<QueryDiscoveryCountDocument> =
    QueryDiscoveryCountDocument.parse(raw).impactFailure(ImpactPathProjectionFailure::Count)

private fun QueryImpactWitnessEntry.NativeRead.projectNativeRead(): ImpactProjected<ImpactWitnessDocument> =
    observation.source.impactDocument().impactZip(observation.domain.impactDocument()).impactThen { (source, domain) ->
        count(observationOrdinal.value)
            .impactZip(count(observation.examinedWorkUnits.value))
            .impactZip(count(observation.retainedBytes))
            .impactZip(count(observation.transfers.size))
            .impactZip(count(observation.obligations.size))
            .impactMap { (counts, obligations) ->
                ImpactWitnessDocument.NativeRead(
                    counts.first.first.first,
                    source,
                    domain,
                    counts.first.first.second,
                    counts.first.second,
                    when (observation.terminal) {
                        ValueFlowTerminal.SupportedDomainExhausted ->
                            ImpactNativeObservationTerminalDocument.SUPPORTED_DOMAIN_EXHAUSTED
                        ValueFlowTerminal.Unresolved -> ImpactNativeObservationTerminalDocument.UNRESOLVED
                    },
                    counts.second,
                    obligations,
                )
            }
    }
