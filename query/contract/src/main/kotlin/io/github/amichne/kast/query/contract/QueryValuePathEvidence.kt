package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.ConsumerRepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector

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
            is QueryImpactTerminal.Unresolved.PeerContinuation ->
                boundary.storageBytes(g).saturatedAdd(connection.storageBytes(g))
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
            .saturatedAdd(g.collection(readReceipts.values) { g.receiptStorage(it) })
            .saturatedAdd(g.collection(readReceipts.values) { g.receiptStorage(it) })
            .saturatedAdd(g.collection(paths) { it.storageBytes(g) })
            .saturatedAdd(g.collection(requestedSites) { it.storageBytes(g) })
            .saturatedAdd(g.collection(siteAccounting) { it.storageBytes(g) })
            .saturatedAdd(g.collection(peerBoundaries) { it.storageBytes(g) })
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
            .saturatedAdd(g.collection(requestedSites) { it.storageBytes(g) })
            .saturatedAdd(g.collection(peerBoundaries) { it.storageBytes(g) })
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

internal fun QueryImpactRequestedSite.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.node(proof) { site.storageBytes(g) }
            .saturatedAdd(
                g.node(request) {
                    request.enclosing
                        .storageBytes(g)
                        .saturatedAdd(g.node(request.anchor) { 0L })
                        .saturatedAdd(
                            g.node(request.role) {
                                when (val role = request.role) {
                                    is ValueSiteRoleClaim.Argument ->
                                        role.expectedCallable
                                            .storageBytes(g)
                                            .saturatedAdd(g.node(role.invocationAnchor) { 0L })
                                    ValueSiteRoleClaim.ExpressionResult,
                                    ValueSiteRoleClaim.LocalBinding,
                                    ValueSiteRoleClaim.LocalRead,
                                    ValueSiteRoleClaim.Return,
                                    ValueSiteRoleClaim.PropertyAssignment -> 0L
                                }
                            }
                        )
                        .saturatedAdd(g.node(request.budget) { g.node(request.budget.resources) { 0L } })
                }
            )
    }

internal fun QueryImpactSiteAccounting.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        requested
            .storageBytes(g)
            .saturatedAdd(
                g.node(outcome) {
                    when (val current = outcome) {
                        is QueryImpactSiteOutcome.Reached ->
                            g.collection(current.pathOrdinals) { g.node(it) { 0L } }
                                .saturatedAdd(g.collection(current.exclusions) { it.storageBytes(g) })
                        is QueryImpactSiteOutcome.Excluded -> g.collection(current.exclusions) { it.storageBytes(g) }
                        QueryImpactSiteOutcome.RelationshipUnproven -> 0L
                    }
                }
            )
    }

private fun QueryImpactSiteExclusion.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) { g.node(exclusion) { exclusion.site.storageBytes(g).saturatedAdd(exclusion.domain.storageBytes(g)) } }

private fun SymbolSelector.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.node(lease) { 0L }
            .saturatedAdd(g.node(file) { g.text(file.stableValue) })
            .saturatedAdd(g.node(range) { 0L })
            .saturatedAdd(g.text(name.value))
            .saturatedAdd(g.text(compilerIdentity.value))
            .saturatedAdd(g.text(fingerprint.value))
            .saturatedAdd(scope.storageBytes(g))
            .saturatedAdd(constraints.storageBytes(g))
            .saturatedAdd(signature.storageBytes(g))
            .saturatedAdd(
                g.node(qualifiedIdentity) {
                    when (val identity = qualifiedIdentity) {
                        is ExactDeclarationQualifiedIdentity.Available -> g.text(identity.value)
                        ExactDeclarationQualifiedIdentity.Unavailable -> 0L
                    }
                }
            )
    }

internal fun QueryImpactPeerBoundary.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) { model.storageBytes(g).saturatedAdd(target.storageBytes(g)) }

internal fun QueryImpactPeerSiteAdmission.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        selection
            .storageBytes(g)
            .saturatedAdd(
                g.node(acquisition) {
                    g.node(acquisition.completedAuthority) { 0L }
                        .saturatedAdd(g.node(acquisition.grant) { g.node(acquisition.grant.resources) { 0L } })
                }
            )
    }
