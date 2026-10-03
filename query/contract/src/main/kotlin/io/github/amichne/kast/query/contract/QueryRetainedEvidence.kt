package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal const val RETAINED_STATE_BASE_BYTES = 512L
private const val RETAINED_STATE_OVERHEAD_MULTIPLIER = 8L
private const val MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT = 3L

/** Authority, copied coverage, and resource evidence retained with immutable rows. */
internal fun Refinement<QueryCount, QueryCountFailure>.refinedCount(): QueryCount =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("A collection size cannot be negative")
    }

internal fun QueryCoverage.copyCoverage(): QueryCoverage =
    when (this) {
        is QueryCoverage.Complete -> copy()
        is QueryCoverage.Qualified ->
            when (val copied = QueryCoverage.Qualified.create(knownMinimum, limitations.toSet())) {
                is Refinement.Refined -> copied.value
                is Refinement.Rejected -> error("A qualified query result lost its limitations")
            }
    }

internal fun QuerySymbol.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    selector.lease != lease ||
        textMatches.values.any { it.lease != lease } ||
        connections.any { fact ->
            fact.authority != lease.identity ||
                fact.subject.lease != lease ||
                fact.source.lease != lease ||
                fact.target.lease != lease
        }

internal fun QueryResult.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    rows.hasForeignBasis(lease) ||
        failures.any { it.hasForeignBasis(lease) } ||
        omissions.any { it.subject.lease != lease } ||
        walkObservations.any { it.subject.lease != lease } ||
        referenceObservations.any { it.target.lease != lease || it.authority != lease.identity } ||
        discoveryObservations.any { it.authority != lease } ||
        relationObservations.any { it.question.subject.lease != lease }

private fun QueryRows.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    when (val resultRows = this) {
        is QueryRows.ImpactWitness -> resultRows.view.ledger.hasForeignBasis(lease)
        is QueryRows.Symbols -> resultRows.values.any { it.hasForeignBasis(lease) }
        is QueryRows.ValuePaths ->
            resultRows.values.any { it.hasForeignBasis(lease) } ||
                when (val witness = resultRows.accounting) {
                    QueryValuePathAccounting.EvidenceOnly -> false
                    is QueryValuePathAccounting.Investigated -> witness.ledger.hasForeignBasis(lease)
                }
        is QueryRows.Occurrences ->
            resultRows.values.any { occurrence ->
                when (occurrence) {
                    is QueryOccurrence.Reference ->
                        occurrence.value.target.lease != lease || occurrence.value.authority != lease.identity
                    is QueryOccurrence.Declaration -> occurrence.symbol.hasForeignBasis(lease)
                }
            }
        is QueryRows.Bindings ->
            resultRows.values.any { row ->
                row.left.value.symbol.hasForeignBasis(lease) || row.right.value.symbol.hasForeignBasis(lease)
            }
    }

internal fun QueryWalkCoverage.isTerminallyIncomplete(): Boolean =
    this is QueryWalkCoverage.TerminalIncomplete ||
        (this is QueryWalkCoverage.Resumable &&
            io.github.amichne.kast.traversal.contract.TraversalLimitation.ONE_HOP_INCOMPLETE in limitations)

internal fun QueryItemFailure.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    when (this) {
        is QueryItemFailure.Refinement -> candidate.lease != lease
        is QueryItemFailure.Visibility -> selector.lease != lease
        is QueryItemFailure.ExactReference -> selector.lease != lease
        is QueryItemFailure.PredicateUnproven -> selector.lease != lease
        is QueryItemFailure.Source -> selector.lease != lease
        is QueryItemFailure.Relation -> selector.lease != lease
        is QueryItemFailure.Walk -> selector.lease != lease
    }

internal fun retainedStorageBytes(
    rows: List<QuerySymbol>,
    failures: List<QueryItemFailure>,
    omissions: List<QueryRelationOmission>,
    walkObservations: List<QueryWalkObservation>,
    coverage: QueryCoverage,
    progress: QueryContinuationState?,
    lease: SemanticReadAuthority,
    references: List<io.github.amichne.kast.relation.contract.RelationReferenceOccurrence>,
    discoveries: List<QueryDiscoveryObservation>,
    relations: List<QueryRelationObservation>,
): Long {
    val sourceText =
        rows.fold(0L) { size, row ->
            val returned = row.source as? QuerySymbolSource.Returned
            size.saturatedAdd(returned?.value?.text?.utf8UpperBound() ?: 0L)
        }
    val relationEvidence =
        rows.fold(0L) { size, row ->
            row.connections.fold(size) { total, fact ->
                total.saturatedAdd(fact.canonicalProjection().utf8UpperBound())
            }
        }
    val checkpoint = (progress as? QueryContinuationState.Resumable)?.checkpoint
    val priorRetainedSource = (checkpoint?.plan as? AdmittedQueryPlan.Retained)?.source?.retainedBytes ?: 0L
    return RETAINED_STATE_BASE_BYTES.saturatedAdd(rows.toString().utf8UpperBound())
        .saturatedAdd(failures.toString().utf8UpperBound())
        .saturatedAdd(omissions.toString().utf8UpperBound())
        .saturatedAdd(walkObservations.toString().utf8UpperBound())
        .saturatedAdd(
            walkObservations.sumOf { observation ->
                observation.scopeExclusions.sumOf { it.exclusion.canonicalProjection().utf8UpperBound() }
            }
        )
        .saturatedAdd(coverage.toString().utf8UpperBound())
        .saturatedAdd(lease.toString().utf8UpperBound())
        .saturatedAdd(sourceText)
        .saturatedAdd(rows.fold(0L) { size, row -> size.saturatedAdd(row.textMatches.projectedUtf8Size()) })
        .saturatedAdd(relationEvidence)
        .saturatedAdd(references.sumOf { it.retainedBytes })
        .saturatedAdd(discoveries.sumOf { it.retainedBytes })
        .saturatedAdd(relations.sumOf { it.retainedBytes })
        .saturatedAdd(checkpoint?.retainedBytes ?: 0L)
        .saturatedAdd(priorRetainedSource)
        .saturatedMultiply(RETAINED_STATE_OVERHEAD_MULTIPLIER)
}

internal fun String.utf8UpperBound(): Long = length.toLong().saturatedMultiply(MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT)

internal fun Long.saturatedAdd(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

internal fun Long.saturatedMultiply(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other
