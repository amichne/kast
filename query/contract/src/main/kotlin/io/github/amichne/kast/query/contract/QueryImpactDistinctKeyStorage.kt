package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ReviewedDirectFormalKey
import io.github.amichne.kast.relation.contract.ReviewedDistinctStringKeys
import io.github.amichne.kast.relation.contract.ReviewedEntryKeyEvidence
import io.github.amichne.kast.relation.contract.ReviewedStringListArtifact
import io.github.amichne.kast.relation.contract.ReviewedStringListInput
import io.github.amichne.kast.relation.contract.ReviewedStringVarargArgument
import io.github.amichne.kast.relation.contract.ReviewedVarargFormal

internal fun ReviewedEntryKeyEvidence.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            ReviewedEntryKeyEvidence.Conditional -> 0L
            is ReviewedEntryKeyEvidence.Distinct -> proof.storageBytes(g)
        }
    }

internal fun ReviewedDistinctStringKeys.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        input
            .storageBytes(g)
            .saturatedAdd(key.storageBytes(g))
            .saturatedAdd(associationInvocation.storageBytes(g))
            .saturatedAdd(g.node(receiverRange) { 0L })
    }

internal fun ReviewedDirectFormalKey.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        binding
            .storageBytes(g)
            .saturatedAdd(formal.storageBytes(g))
            .saturatedAdd(tupleOrigin.storageBytes(g))
            .saturatedAdd(g.node(keyRange) { 0L })
            .saturatedAdd(g.node(returnRange) { 0L })
    }

internal fun ReviewedStringListInput.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        formal
            .storageBytes(g)
            .saturatedAdd(artifact.storageBytes(g))
            .saturatedAdd(g.collection(actualArgumentRanges) { g.node(it) { 0L } })
            .saturatedAdd(g.collection(arguments) { it.storageBytes(g) })
    }

private fun ReviewedVarargFormal.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        invocation.storageBytes(g).saturatedAdd(target.storageBytes(g))
    }

private fun ReviewedStringVarargArgument.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        formal.storageBytes(g).saturatedAdd(g.node(range) { 0L }).saturatedAdd(g.text(value))
    }

internal fun ReviewedStringListArtifact.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.text(binary.value)
            .saturatedAdd(g.text(sources.value))
            .saturatedAdd(
                g.collection(sourceSections) { section ->
                    g.node(section) { g.text(section.sourceEntry).saturatedAdd(g.text(section.sourceEntrySha256)) }
                }
            )
            .saturatedAdd(
                g.collection(classes) { evidence ->
                    g.node(evidence) { g.text(evidence.entry).saturatedAdd(g.text(evidence.sha256)) }
                }
            )
    }
