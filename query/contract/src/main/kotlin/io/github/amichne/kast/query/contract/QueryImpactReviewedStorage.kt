package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedGroupBucket
import io.github.amichne.kast.relation.contract.ReviewedInputEntryOrigin
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedMapEntryCorrelation
import io.github.amichne.kast.relation.contract.ReviewedMapEntryOrigin
import io.github.amichne.kast.relation.contract.ReviewedMappedEntryOrigin
import io.github.amichne.kast.relation.contract.ReviewedPreservedKeyOrigin
import io.github.amichne.kast.relation.contract.ReviewedTupleOrigin
import io.github.amichne.kast.relation.contract.ReviewedValueAuthority
import io.github.amichne.kast.relation.contract.ReviewedValueRelation

internal fun ReviewedValueAuthority.storageBytes(g: QueryImpactRetainedGraph): Long =
    when (this) {
        is ReviewedCallbackValueBinding -> storageBytes(g)
        is ReviewedCallValueBinding ->
            g.node(this) {
                invocation
                    .storageBytes(g)
                    .saturatedAdd(g.node(selected) { target.storageBytes(g) })
                    .saturatedAdd(artifact.storageBytes(g))
                    .saturatedAdd(entryReadOrder.storageBytes(g))
            }
    }

internal fun ReviewedLibraryArtifact.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.text(binaryArtifact.value)
            .saturatedAdd(g.text(sourceArtifact.value))
            .saturatedAdd(g.text(sourceEntry))
            .saturatedAdd(g.text(sourceEntrySha256))
            .saturatedAdd(g.text(facadeSha256))
            .saturatedAdd(g.text(implementationSha256))
            .saturatedAdd(g.collection(sourceSections) { g.node(it) { 0L } })
            .saturatedAdd(
                g.collection(helpers) { helper ->
                    g.node(helper) {
                        g.text(helper.sourceEntry)
                            .saturatedAdd(g.text(helper.sourceEntrySha256))
                            .saturatedAdd(g.collection(helper.sourceSections) { g.node(it) { 0L } })
                            .saturatedAdd(
                                g.collection(helper.classes) { evidence ->
                                    g.node(evidence) { g.text(evidence.entry).saturatedAdd(g.text(evidence.sha256)) }
                                }
                            )
                    }
                }
            )
    }

internal fun ReviewedTupleOrigin.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        binding.storageBytes(g).saturatedAdd(callStack.storageBytes(g))
    }

internal fun ReviewedMapEntryOrigin.storageBytes(g: QueryImpactRetainedGraph): Long =
    when (this) {
        is ReviewedGroupBucket -> storageBytes(g)
        is ReviewedMapEntryCorrelation -> storageBytes(g)
        is ReviewedMappedEntryOrigin -> storageBytes(g)
        is ReviewedInputEntryOrigin -> storageBytes(g)
        is ReviewedPreservedKeyOrigin -> storageBytes(g)
    }

internal fun ReviewedMappedEntryOrigin.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        family
            .storageBytes(g)
            .saturatedAdd(g.node(returnRange) { 0L })
            .saturatedAdd(returnExecution.storageBytes(g))
            .saturatedAdd(QUERY_ENTRY_SELECTION_BYTES)
    }

internal fun ReviewedMapEntryCorrelation.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        binding
            .storageBytes(g)
            .saturatedAdd(tupleOrigin.storageBytes(g))
            .saturatedAdd(g.node(returnRange) { 0L })
            .saturatedAdd(returnExecution.storageBytes(g))
            .saturatedAdd(callStack.storageBytes(g))
            .saturatedAdd(keyEvidence.storageBytes(g))
    }

internal fun ReviewedValueRelation.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is ReviewedValueRelation.FilteredElements,
            ReviewedValueRelation.NonNullCallbackResult,
            is ReviewedValueRelation.CallbackCorrespondence,
            is ReviewedValueRelation.ReorderedElements -> 0L
            is ReviewedValueRelation.TupleConstruction -> origin.storageBytes(g)
            is ReviewedValueRelation.TupleProjection -> origin.storageBytes(g)
            is ReviewedValueRelation.CallbackReceiverInput -> receiver.storageBytes(g)
            is ReviewedValueRelation.GroupPlacement -> entry.storageBytes(g)
            is ReviewedValueRelation.MappedValue -> entry.storageBytes(g)
            is ReviewedValueRelation.EntryInput -> entry.storageBytes(g)
            is ReviewedValueRelation.PreservedKey -> entry.storageBytes(g)
            is ReviewedValueRelation.EntryProjection -> entry.storageBytes(g)
            is ReviewedValueRelation.AssociationComponent -> entry.storageBytes(g)
        }
    }
