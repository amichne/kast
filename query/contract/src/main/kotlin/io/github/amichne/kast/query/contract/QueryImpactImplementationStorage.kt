package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationScopeExclusion
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueImplementationDispatch
import io.github.amichne.kast.relation.contract.ValueSourceOwnership
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot

internal fun ValueCallTarget.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            ValueCallTarget.Direct -> 0L
            is ValueCallTarget.Implementation ->
                dispatch.storageBytes(g).saturatedAdd(relation.storageBytes(g)).saturatedAdd(ownership.storageBytes(g))
        }
    }

internal fun ValueFlowObligation.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) { site.storageBytes(g).saturatedAdd(evidence.storageBytes(g)) }

private fun ValueFlowObligationEvidence.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            ValueFlowObligationEvidence.None -> 0L
            is ValueFlowObligationEvidence.ImplementationDispatch -> dispatch.storageBytes(g)
        }
    }

internal fun ValueImplementationDispatch.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        batch.storageBytes(g).saturatedAdd(coverage.storageBytes(g)).saturatedAdd(retainedIdentityBytes)
    }

private fun RelationBatch.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        request
            .storageBytes(g)
            .saturatedAdd(g.collection(facts) { it.storageBytes(g) })
            .saturatedAdd(g.collection(omissions) { it.storageBytes(g) })
            .saturatedAdd(g.collection(scopeExclusions) { it.storageBytes(g) })
            .saturatedAdd(g.collection(referenceOccurrences) { g.node(it) { it.retainedBytes } })
            .saturatedAdd(g.collection(callbackObservations) { g.node(it) { it.retainedBytes } })
            .saturatedAdd(g.collection(callableObservations) { g.node(it) { it.retainedBytes } })
    }

private fun RelationFact.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        subject
            .storageBytes(g)
            .saturatedAdd(source.storageBytes(g))
            .saturatedAdd(target.storageBytes(g))
            .saturatedAdd(g.node(authority) { 0L })
            .saturatedAdd(
                g.node(occurrence) {
                    g.node(occurrence.file) { g.text(occurrence.file.stableValue) }
                        .saturatedAdd(g.node(occurrence.range) { 0L })
                }
            )
    }

private fun RelationOmissionEvidence.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.node(measurement) {
                when (measurement) {
                    is RelationOmissionMeasurement.ObservedOnPage,
                    RelationOmissionMeasurement.UnmeasuredOnPage -> 0L
                }
            }
            .saturatedAdd(
                g.node(samples) {
                    g.collection(samples.locations) { location ->
                        g.node(location) {
                            g.node(location.file) { g.text(location.file.stableValue) }
                                .saturatedAdd(g.node(location.range) { 0L })
                        }
                    }
                }
            )
    }

private fun RelationScopeExclusion.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.text(subject.value)
            .saturatedAdd(g.node(basis) { 0L })
            .saturatedAdd(requestedDomain.storageBytes(g))
            .saturatedAdd(g.text(effectiveDomain.value))
            .saturatedAdd(effectiveScope.storageBytes(g))
            .saturatedAdd(effectiveConstraints.storageBytes(g))
            .saturatedAdd(
                g.node(occurrence) {
                    g.node(occurrence.file) { g.text(occurrence.file.stableValue) }
                        .saturatedAdd(g.node(occurrence.range) { 0L })
                }
            )
            .saturatedAdd(target.storageBytes(g))
    }

private fun CompilerGroundedSymbolEvidence.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.node(file) { g.text(file.stableValue) }
            .saturatedAdd(g.node(range) { 0L })
            .saturatedAdd(g.text(name.value))
            .saturatedAdd(g.text(compilerIdentity.value))
            .saturatedAdd(signature.storageBytes(g))
    }

private fun ValueImplementationCoverage.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is ValueImplementationCoverage.Exhausted -> g.node(evidence) { 0L }
            is ValueImplementationCoverage.Incomplete ->
                g.node(evidence) {
                    g.collection(evidence.limitations) { g.node(it) { 0L } }
                        .saturatedAdd(
                            when (val current = evidence) {
                                is RelationIncompleteCoverage.TerminalIncomplete -> 0L
                                is RelationIncompleteCoverage.Resumable ->
                                    g.node(current.continuation) {
                                        g.text(current.continuation.subject.value)
                                            .saturatedAdd(g.text(current.continuation.scope.value))
                                            .saturatedAdd(g.node(current.continuation.authority) { 0L })
                                            .saturatedAdd(g.text(current.continuation.fingerprint.value))
                                            .saturatedAdd(
                                                g.node(current.continuation.nextProviderCursor) {
                                                    g.text(
                                                        current.continuation.nextProviderCursor.consumedPrefixDigest
                                                            .value
                                                    )
                                                }
                                            )
                                            .saturatedAdd(
                                                g.collection(current.continuation.retainedLimitations) {
                                                    g.node(it) { 0L }
                                                }
                                            )
                                            .saturatedAdd(g.providerState(current.continuation.providerState))
                                    }
                            }
                        )
                }
        }
    }

private fun ValueSourceOwnership.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.node(declaration) {
                g.text(declaration.compiler.value)
                    .saturatedAdd(g.node(declaration.file) { g.text(declaration.file.stableValue) })
                    .saturatedAdd(g.node(declaration.range) { 0L })
            }
            .saturatedAdd(g.node(basis) { 0L })
            .saturatedAdd(root.storageBytes(g))
    }

private fun ModelOwnedSourceRoot.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.text(module.value)
            .saturatedAdd(
                g.node(project) {
                    g.text(project.buildRoot.value).saturatedAdd(g.text(project.projectPath.value))
                }
            )
            .saturatedAdd(g.text(sourceSet.value))
            .saturatedAdd(g.text(sourceRoot.value))
    }
