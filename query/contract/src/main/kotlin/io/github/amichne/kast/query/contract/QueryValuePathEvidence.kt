package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.ConsumerRepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationRule

/** Conservative storage of the retained object graph, preserving shared proof references. */
fun QueryImpactPath.retainedStorageBytes(): Long = QueryImpactRetainedGraph().path(this)

internal fun QueryImpactPath.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        producer
            .storageBytes(g)
            .saturatedAdd(g.collection(steps) { it.storageBytes(g) })
            .saturatedAdd(representation.storageBytes(g))
            .saturatedAdd(terminal.storageBytes(g))
    }

internal fun QueryImpactRepresentation.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            QueryImpactRepresentation.NotModeled -> 0L
            is QueryImpactRepresentation.Present -> evidence.storageBytes(g)
        }
    }

internal fun QueryImpactStep.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is QueryImpactStep.Compiler -> transfer.storageBytes(g)
            is QueryImpactStep.ModeledRepresentation -> application.storageBytes(g)
            is QueryImpactStep.ModeledBoundary -> connection.storageBytes(g)
        }
    }

private fun ConsumerRepresentationEvidence.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        rule
            .storageBytes(g)
            .saturatedAdd(
                when (this) {
                    is ConsumerRepresentationEvidence.Satisfied -> evidence.storageBytes(g)
                    is ConsumerRepresentationEvidence.Different -> evidence.storageBytes(g)
                    is ConsumerRepresentationEvidence.Unknown -> evidence.storageBytes(g)
                }
            )
    }

private fun QueryImpactTerminal.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is QueryImpactTerminal.Consumer -> evidence.storageBytes(g)
            is QueryImpactTerminal.ModeledTerminal -> boundary.storageBytes(g)
            is QueryImpactTerminal.Unresolved.ExecutionStop -> stop.storageBytes(g)
            is QueryImpactTerminal.Unresolved.ReadRejected -> rejection.storageBytes(g)
            is QueryImpactTerminal.Unresolved.Flow -> g.node(obligation) { obligation.site.storageBytes(g) }
            is QueryImpactTerminal.Unresolved.Boundary -> boundary.storageBytes(g)
            is QueryImpactTerminal.SupportedDomainEnd -> observation.storageBytes(g)
            is QueryImpactTerminal.ExplicitScopeExclusion ->
                g.node(exclusion) {
                    exclusion.site.storageBytes(g).saturatedAdd(exclusion.domain.storageBytes(g))
                }
        }
    }

fun QueryImpactLedger.retainedStorageBytes(): Long = QueryImpactRetainedGraph().ledger(this)

internal fun QueryImpactLedger.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.collection(producerEvidence) { producer ->
                g.node(producer) {
                    when (producer) {
                        is QueryImpactProducerEvidence.Invocation -> producer.producer.storageBytes(g)
                        is QueryImpactProducerEvidence.SiteOnly -> producer.site.storageBytes(g)
                    }
                }
            }
            .saturatedAdd(g.collection(seeds) { it.storageBytes(g) })
            .saturatedAdd(domain.storageBytes(g))
            .saturatedAdd(g.collection(representationModels) { it.storageBytes(g) })
            .saturatedAdd(g.collection(boundaryModels) { it.storageBytes(g) })
            .saturatedAdd(g.collection(readRejections) { it.storageBytes(g) })
            .saturatedAdd(g.collection(observations) { it.storageBytes(g) })
            .saturatedAdd(g.collection(paths) { it.storageBytes(g) })
            .saturatedAdd(
                g.node(closure) {
                    when (val current = closure) {
                        QueryImpactClosure.Discharged -> 0L
                        is QueryImpactClosure.Unresolved -> g.collection(current.required) { g.node(it) { 0L } }
                    }
                }
            )
    }

internal fun QueryImpactSource.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.collection(producers) { it.storageBytes(g) }
            .saturatedAdd(g.collection(representationModels) { it.storageBytes(g) })
            .saturatedAdd(g.collection(boundaryModels) { it.storageBytes(g) })
            .saturatedAdd(domain.storageBytes(g))
            .saturatedAdd(g.node(lease) { 0L })
    }

internal fun QueryImpactProducer.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        site.storageBytes(g).saturatedAdd(invocation.storageBytes(g))
    }

internal fun QueryImpactReadRejection.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        source.storageBytes(g).saturatedAdd(domain.storageBytes(g))
    }

/** Full detached checkpoint prefix charge, independent of any terminal claim. */
fun QueryImpactStep.retainedStorageBytes(): Long = QueryImpactRetainedGraph().step(this)

fun QueryImpactRepresentation.retainedStorageBytes(): Long = QueryImpactRetainedGraph().representation(this)

fun RepresentationRule.retainedStorageBytes(): Long = QueryImpactRetainedGraph().representationModel(this)

fun BoundaryModel.retainedStorageBytes(): Long = QueryImpactRetainedGraph().boundaryModel(this)

private fun QueryImpactExecutionStop.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        source
            .storageBytes(g)
            .saturatedAdd(
                when (this) {
                    is QueryImpactExecutionStop.Cycle ->
                        producer.storageBytes(g).saturatedAdd(g.collection(prefix) { it.storageBytes(g) })
                    is QueryImpactExecutionStop.CheckpointCapacity -> 0L
                }
            )
    }
