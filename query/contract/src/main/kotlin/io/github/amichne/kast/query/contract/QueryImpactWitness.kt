package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueTransfer
import java.util.Collections

enum class QueryImpactWitnessSection {
    FINDINGS,
    SITE_ACCOUNTING,
    PRODUCERS,
    MODELS,
    NATIVE_READS,
    READ_REJECTIONS,
}

enum class QueryImpactWitnessFailure {
    NEGATIVE_ORDINAL,
    INVALID_RANGE,
    PAGE_TOO_LARGE,
    SECTION_TOO_LARGE,
}

@JvmInline
value class QueryImpactWitnessOrdinal private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<QueryImpactWitnessOrdinal, QueryImpactWitnessFailure> =
            if (raw < 0) Refinement.Rejected(QueryImpactWitnessFailure.NEGATIVE_ORDINAL)
            else Refinement.Refined(QueryImpactWitnessOrdinal(raw))
    }
}

sealed interface QueryImpactWitnessEntry {
    data class SiteAccounting(val accounting: QueryImpactSiteAccounting) : QueryImpactWitnessEntry

    data class Finding(val finding: QueryImpactFinding) : QueryImpactWitnessEntry

    data class Producer(val evidence: QueryImpactProducerEvidence) : QueryImpactWitnessEntry

    data class RepresentationModel(val rule: RepresentationRule) : QueryImpactWitnessEntry

    data class BoundaryModel(val model: io.github.amichne.kast.relation.contract.BoundaryModel) :
        QueryImpactWitnessEntry

    data class NativeRead(val observationOrdinal: QueryImpactWitnessOrdinal, val observation: ValueFlowStep) :
        QueryImpactWitnessEntry

    data class CompilerTransfer(
        val observationOrdinal: QueryImpactWitnessOrdinal,
        val transferOrdinal: QueryImpactWitnessOrdinal,
        val transfer: ValueTransfer,
    ) : QueryImpactWitnessEntry

    data class FlowObligation(
        val observationOrdinal: QueryImpactWitnessOrdinal,
        val obligationOrdinal: QueryImpactWitnessOrdinal,
        val obligation: ValueFlowObligation,
    ) : QueryImpactWitnessEntry

    data class ReadRejected(val rejection: QueryImpactReadRejection) : QueryImpactWitnessEntry
}

data class QueryImpactWitnessRecord(val ordinal: QueryImpactWitnessOrdinal, val evidence: QueryImpactWitnessEntry)

/** A pure bounded view of one existing immutable ledger; neither retained state nor a semantic execution authority. */
class QueryImpactWitnessView
private constructor(
    val ledger: QueryImpactLedger,
    val section: QueryImpactWitnessSection,
    val firstOrdinal: QueryImpactWitnessOrdinal,
    val nextOrdinal: QueryImpactWitnessOrdinal,
    val sectionCount: QueryImpactWitnessOrdinal,
    val entries: List<QueryImpactWitnessRecord>,
) {
    companion object {
        fun create(
            ledger: QueryImpactLedger,
            section: QueryImpactWitnessSection,
            start: Int,
            end: Int,
        ): Refinement<QueryImpactWitnessView, QueryImpactWitnessFailure> {
            if (start < 0 || end < start) return Refinement.Rejected(QueryImpactWitnessFailure.INVALID_RANGE)
            if (end.toLong() - start > WITNESS_PAGE_MAX_ITEMS)
                return Refinement.Rejected(QueryImpactWitnessFailure.PAGE_TOO_LARGE)
            val all =
                when (val collected = entries(ledger, section)) {
                    is Refinement.Refined -> collected.value
                    is Refinement.Rejected -> return collected
                }
            if (end > all.size) return Refinement.Rejected(QueryImpactWitnessFailure.INVALID_RANGE)
            val first =
                when (val value = QueryImpactWitnessOrdinal.parse(start)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val next =
                when (val value = QueryImpactWitnessOrdinal.parse(end)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            val count =
                when (val value = QueryImpactWitnessOrdinal.parse(all.size)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            return Refinement.Refined(
                QueryImpactWitnessView(
                    ledger,
                    section,
                    first,
                    next,
                    count,
                    Collections.unmodifiableList(all.subList(start, end).toList()),
                )
            )
        }

        fun count(
            ledger: QueryImpactLedger,
            section: QueryImpactWitnessSection,
        ): Refinement<QueryImpactWitnessOrdinal, QueryImpactWitnessFailure> {
            val total =
                when (section) {
                    QueryImpactWitnessSection.SITE_ACCOUNTING -> ledger.siteAccounting.size.toLong()
                    QueryImpactWitnessSection.FINDINGS -> ledger.paths.size.toLong()
                    QueryImpactWitnessSection.PRODUCERS -> ledger.producerEvidence.size.toLong()
                    QueryImpactWitnessSection.MODELS ->
                        ledger.representationModels.size.toLong() + ledger.boundaryModels.size
                    QueryImpactWitnessSection.NATIVE_READS ->
                        ledger.observations.sumOf { 1L + it.transfers.size + it.obligations.size }
                    QueryImpactWitnessSection.READ_REJECTIONS -> ledger.readRejections.size.toLong()
                }
            return if (total > Int.MAX_VALUE) Refinement.Rejected(QueryImpactWitnessFailure.SECTION_TOO_LARGE)
            else QueryImpactWitnessOrdinal.parse(total.toInt())
        }

        private fun entries(
            ledger: QueryImpactLedger,
            section: QueryImpactWitnessSection,
        ): Refinement<List<QueryImpactWitnessRecord>, QueryImpactWitnessFailure> {
            when (val bound = count(ledger, section)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return bound
            }
            val all =
                when (val collected = entriesForSection(ledger, section)) {
                    is Refinement.Refined -> collected.value
                    is Refinement.Rejected -> return collected
                }
            return all.withWitnessOrdinals(::QueryImpactWitnessRecord)
        }
    }
}

private fun entriesForSection(
    ledger: QueryImpactLedger,
    section: QueryImpactWitnessSection,
): Refinement<List<QueryImpactWitnessEntry>, QueryImpactWitnessFailure> =
    when (section) {
        QueryImpactWitnessSection.FINDINGS -> findingEntries(ledger)
        QueryImpactWitnessSection.SITE_ACCOUNTING ->
            Refinement.Refined(ledger.siteAccounting.map(QueryImpactWitnessEntry::SiteAccounting))
        QueryImpactWitnessSection.PRODUCERS ->
            Refinement.Refined(ledger.producerEvidence.map(QueryImpactWitnessEntry::Producer))
        QueryImpactWitnessSection.MODELS ->
            Refinement.Refined(
                ledger.representationModels.map(QueryImpactWitnessEntry::RepresentationModel) +
                    ledger.boundaryModels.map(QueryImpactWitnessEntry::BoundaryModel)
            )
        QueryImpactWitnessSection.READ_REJECTIONS ->
            Refinement.Refined(ledger.readRejections.map(QueryImpactWitnessEntry::ReadRejected))
        QueryImpactWitnessSection.NATIVE_READS -> nativeEntries(ledger.observations)
    }

private fun findingEntries(
    ledger: QueryImpactLedger
): Refinement<List<QueryImpactWitnessEntry>, QueryImpactWitnessFailure> {
    val result = mutableListOf<QueryImpactWitnessEntry>()
    for (ordinal in ledger.paths.indices) {
        when (val finding = QueryImpactFinding.fromOriginalPath(ledger, ordinal)) {
            is Refinement.Refined -> result += QueryImpactWitnessEntry.Finding(finding.value)
            is Refinement.Rejected -> return finding
        }
    }
    return Refinement.Refined(result)
}

private fun nativeEntries(
    observations: List<ValueFlowStep>
): Refinement<List<QueryImpactWitnessEntry>, QueryImpactWitnessFailure> {
    val entries = mutableListOf<QueryImpactWitnessEntry>()
    for ((index, observation) in observations.withIndex()) {
        val ordinal =
            when (val admitted = QueryImpactWitnessOrdinal.parse(index)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        when (val collected = observationEntries(ordinal, observation)) {
            is Refinement.Refined -> entries += collected.value
            is Refinement.Rejected -> return collected
        }
    }
    return Refinement.Refined(entries)
}

private fun observationEntries(
    ordinal: QueryImpactWitnessOrdinal,
    observation: ValueFlowStep,
): Refinement<List<QueryImpactWitnessEntry>, QueryImpactWitnessFailure> {
    val transfers =
        when (
            val collected =
                observation.transfers.withWitnessOrdinals { position, transfer ->
                    QueryImpactWitnessEntry.CompilerTransfer(ordinal, position, transfer)
                }
        ) {
            is Refinement.Refined -> collected.value
            is Refinement.Rejected -> return collected
        }
    val obligations =
        when (
            val collected =
                observation.obligations.withWitnessOrdinals { position, obligation ->
                    QueryImpactWitnessEntry.FlowObligation(ordinal, position, obligation)
                }
        ) {
            is Refinement.Refined -> collected.value
            is Refinement.Rejected -> return collected
        }
    return Refinement.Refined(
        listOf(QueryImpactWitnessEntry.NativeRead(ordinal, observation)) + transfers + obligations
    )
}

private fun <T, U> List<T>.withWitnessOrdinals(
    project: (QueryImpactWitnessOrdinal, T) -> U
): Refinement<List<U>, QueryImpactWitnessFailure> {
    val result = mutableListOf<U>()
    for ((index, value) in withIndex()) {
        val ordinal =
            when (val admitted = QueryImpactWitnessOrdinal.parse(index)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        result += project(ordinal, value)
    }
    return Refinement.Refined(result)
}

private const val WITNESS_PAGE_MAX_ITEMS = 100
