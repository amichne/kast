package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ReviewedArtifactEvidence
import io.github.amichne.kast.relation.contract.ReviewedEntryDeclarationArtifact
import io.github.amichne.kast.relation.contract.ReviewedEntryReadOrder
import io.github.amichne.kast.relation.contract.ReviewedEntryReadPrefix
import io.github.amichne.kast.relation.contract.ReviewedInputEntryOrigin
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedMapEntryFamily
import io.github.amichne.kast.relation.contract.ReviewedPreservedKeyOrigin

internal fun ReviewedArtifactEvidence.storageBytes(g: QueryImpactRetainedGraph): Long =
    when (this) {
        is ReviewedLibraryArtifact -> storageBytes(g)
        is ReviewedEntryDeclarationArtifact ->
            g.node(this) {
                g.text(binaryArtifact.value)
                    .saturatedAdd(g.text(sourceArtifact.value))
                    .saturatedAdd(g.text(sourceEntry))
                    .saturatedAdd(g.text(sourceEntrySha256))
                    .saturatedAdd(g.collection(sourceSections) { g.node(it) { 0L } })
                    .saturatedAdd(g.node(metadata) { g.text(metadata.entry).saturatedAdd(g.text(metadata.sha256)) })
            }
    }

internal fun ReviewedMapEntryFamily.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) { binding.storageBytes(g).saturatedAdd(callStack.storageBytes(g)) }

internal fun ReviewedInputEntryOrigin.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        family
            .storageBytes(g)
            .saturatedAdd(g.node(receiverRange) { 0L })
            .saturatedAdd(receiverExecution.storageBytes(g))
            .saturatedAdd(QUERY_ENTRY_SELECTION_BYTES)
    }

internal fun ReviewedPreservedKeyOrigin.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        family
            .storageBytes(g)
            .saturatedAdd(g.node(receiverRange) { 0L })
            .saturatedAdd(receiverExecution.storageBytes(g))
            .saturatedAdd(QUERY_ENTRY_SELECTION_BYTES)
    }

internal fun ReviewedEntryReadOrder.storageBytes(g: QueryImpactRetainedGraph): Long =
    when (this) {
        ReviewedEntryReadOrder.Direct -> 0L
        is ReviewedEntryReadOrder.Destructured ->
            g.node(this) { g.node(declarationRange) { 0L }.saturatedAdd(prefix.storageBytes(g)) }
    }

private fun ReviewedEntryReadPrefix.storageBytes(g: QueryImpactRetainedGraph): Long =
    when (this) {
        ReviewedEntryReadPrefix.Empty -> 0L
        is ReviewedEntryReadPrefix.FirstComponent ->
            g.node(this) {
                g.node(invocation) {
                    invocation.invocation
                        .storageBytes(g)
                        .saturatedAdd(invocation.selected.target.storageBytes(g))
                        .saturatedAdd(invocation.artifact.storageBytes(g))
                }
            }
    }

internal const val QUERY_ENTRY_SELECTION_BYTES = 4L
