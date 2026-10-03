package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.ConsumerRepresentationEvidence
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RepresentationCurrent
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationModelApplication
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RepresentationState
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.relation.contract.ValueTransfer

/** Saturating, conservative storage charge visits the full detached evidence rather than opaque object strings. */
fun QueryImpactPath.retainedStorageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(producer.storageBytes())
        .saturatedAdd(steps.fold(0L) { bytes, step -> bytes.saturatedAdd(step.storageBytes()) })
        .saturatedAdd(
            when (val current = representation) {
                QueryImpactRepresentation.NotModeled -> RETAINED_STATE_BASE_BYTES
                is QueryImpactRepresentation.Present -> current.evidence.storageBytes()
            }
        )
        .saturatedAdd(terminal.storageBytes())
        .saturatedMultiply(DETACHED_EVIDENCE_STORAGE_MULTIPLIER)

private fun RelationEndpoint.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(lease.toString().utf8UpperBound())
        .saturatedAdd(file.stableValue.utf8UpperBound())
        .saturatedAdd(name.value.utf8UpperBound())
        .saturatedAdd(qualifiedIdentity.toString().utf8UpperBound())
        .saturatedAdd(compilerIdentity.value.utf8UpperBound())
        .saturatedAdd(signature.canonicalEncoding().value.utf8UpperBound())
        .saturatedAdd(RelationSearchBoundary.RETAINED_SUBJECT.canonical(this).utf8UpperBound())

internal fun ValueSite.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(enclosing.storageBytes()).saturatedAdd(role.storageBytes())

private fun ValueRole.storageBytes(): Long =
    when (this) {
        is ValueRole.Argument -> call.storageBytes()
        ValueRole.ExpressionResult,
        ValueRole.LocalBinding,
        ValueRole.LocalRead,
        ValueRole.Return,
        ValueRole.PropertyAssignment -> RETAINED_STATE_BASE_BYTES
    }

internal fun ValueInvocation.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(enclosing.storageBytes()).saturatedAdd(callable.storageBytes())

private fun ValueSiteIdentity.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(owner.compiler.value.utf8UpperBound())
        .saturatedAdd(owner.file.stableValue.utf8UpperBound())
        .saturatedAdd(basis.toString().utf8UpperBound())
        .saturatedAdd(role.storageBytes())

private fun ValueTransfer.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(source.storageBytes()).saturatedAdd(target.storageBytes())

private fun RepresentationState.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(domain.model.toString().utf8UpperBound())
        .saturatedAdd(domain.states.toString().utf8UpperBound())
        .saturatedAdd(id.value.utf8UpperBound())

internal fun RepresentationRule.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(reference.toString().utf8UpperBound())
        .saturatedAdd(
            when (this) {
                is RepresentationRule.Origin -> output.endpoint.storageBytes().saturatedAdd(state.storageBytes())
                is RepresentationRule.Transfer ->
                    input.endpoint.storageBytes().saturatedAdd(output.endpoint.storageBytes())
                is RepresentationRule.Transformation ->
                    input.endpoint
                        .storageBytes()
                        .saturatedAdd(output.endpoint.storageBytes())
                        .saturatedAdd(from.storageBytes())
                        .saturatedAdd(to.storageBytes())
                is RepresentationRule.ConsumerExpectation ->
                    input.endpoint.storageBytes().saturatedAdd(expected.storageBytes())
            }
        )

private fun RepresentationModelApplication.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(source.storageBytes())
        .saturatedAdd(target.storageBytes())
        .saturatedAdd(invocation.storageBytes())
        .saturatedAdd(
            when (this) {
                is RepresentationModelApplication.Transfer -> rule.storageBytes()
                is RepresentationModelApplication.Transformation -> rule.storageBytes()
            }
        )

private fun BoundaryPosition.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(site.storageBytes())
        .saturatedAdd(contract.toString().utf8UpperBound())
        .saturatedAdd(slot.value.utf8UpperBound())

private fun BoundaryArrival.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(source.storageBytes())
        .saturatedAdd(
            obligations.fold(0L) { bytes, obligation ->
                bytes
                    .saturatedAdd(obligation.position.storageBytes())
                    .saturatedAdd(obligation.required.toString().utf8UpperBound())
            }
        )
        .saturatedAdd(
            when (this) {
                is BoundaryArrival.Connected ->
                    model.reference
                        .toString()
                        .utf8UpperBound()
                        .saturatedAdd(model.source.storageBytes())
                        .saturatedAdd(target.storageBytes())
                        .saturatedAdd(model.assumptions.toString().utf8UpperBound())
                is BoundaryArrival.Terminal ->
                    model.reference.toString().utf8UpperBound().saturatedAdd(model.source.storageBytes())
                is BoundaryArrival.Unresolved -> RETAINED_STATE_BASE_BYTES
            }
        )

private fun QueryImpactStep.storageBytes(): Long =
    when (this) {
        is QueryImpactStep.Compiler -> transfer.storageBytes()
        is QueryImpactStep.ModeledRepresentation -> application.storageBytes()
        is QueryImpactStep.ModeledBoundary -> connection.storageBytes()
    }

private fun RepresentationHistory.storageBytes(): Long =
    when (this) {
        is RepresentationHistory.Origin ->
            rule.storageBytes().saturatedAdd(output.storageBytes()).saturatedAdd(invocation.storageBytes())
        is RepresentationHistory.CompilerTransfer -> transfer.storageBytes()
        is RepresentationModelApplication -> storageBytes()
        is RepresentationHistory.Unmodeled -> source.storageBytes().saturatedAdd(target.storageBytes())
        is RepresentationHistory.BoundaryModel ->
            reference
                .toString()
                .utf8UpperBound()
                .saturatedAdd(source.site.storageBytes())
                .saturatedAdd(target.site.storageBytes())
                .saturatedAdd(source.toString().utf8UpperBound())
                .saturatedAdd(target.toString().utf8UpperBound())
    }

private fun RepresentationEvidence.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(site.storageBytes())
        .saturatedAdd(
            branches.fold(0L) { bytes, branch ->
                bytes
                    .saturatedAdd(
                        when (val current = branch.current) {
                            is RepresentationCurrent.Known -> current.state.storageBytes()
                            is RepresentationCurrent.Unknown -> RETAINED_STATE_BASE_BYTES
                        }
                    )
                    .saturatedAdd(
                        branch.history.fold(0L) { total, history -> total.saturatedAdd(history.storageBytes()) }
                    )
            }
        )

private fun QueryImpactTerminal.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(site.storageBytes())
        .saturatedAdd(
            when (this) {
                is QueryImpactTerminal.Consumer ->
                    evidence.rule
                        .storageBytes()
                        .saturatedAdd(
                            when (val consumer = evidence) {
                                is ConsumerRepresentationEvidence.Satisfied -> consumer.evidence.storageBytes()
                                is ConsumerRepresentationEvidence.Different -> consumer.evidence.storageBytes()
                                is ConsumerRepresentationEvidence.Unknown -> consumer.evidence.storageBytes()
                            }
                        )
                is QueryImpactTerminal.ModeledTerminal -> boundary.storageBytes()
                is QueryImpactTerminal.Unresolved.ExecutionStop -> stop.storageBytes()
                is QueryImpactTerminal.Unresolved.ReadRejected ->
                    RETAINED_STATE_BASE_BYTES.saturatedAdd(rejection.source.storageBytes())
                        .saturatedAdd(rejection.domain.canonical(rejection.source.enclosing).utf8UpperBound())
                is QueryImpactTerminal.Unresolved.Flow -> RETAINED_STATE_BASE_BYTES
                is QueryImpactTerminal.Unresolved.Boundary -> boundary.storageBytes()
                is QueryImpactTerminal.SupportedDomainEnd ->
                    observation.domain.subject
                        .storageBytes()
                        .saturatedAdd(
                            observation.domain.boundary.canonical(observation.domain.subject).utf8UpperBound()
                        )
                        .saturatedAdd(
                            when (val position = observation.domain.position) {
                                RelationReadPosition.Start -> RETAINED_STATE_BASE_BYTES
                                is RelationReadPosition.Resume ->
                                    RETAINED_STATE_BASE_BYTES.saturatedAdd(
                                            position.continuation.providerState.retainedBytes
                                        )
                                        .saturatedAdd(
                                            position.continuation.retainedLimitations.toString().utf8UpperBound()
                                        )
                            }
                        )
                is QueryImpactTerminal.ExplicitScopeExclusion ->
                    exclusion.domain.canonical(exclusion.site.enclosing).utf8UpperBound()
            }
        )

/** Paths are charged at their own conservative rate; the other full ledger fields retain their independent charge. */
fun QueryImpactLedger.retainedStorageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(domain.canonical(seeds.first().enclosing).utf8UpperBound())
        .saturatedAdd(
            producerEvidence.fold(0L) { bytes, producer ->
                bytes.saturatedAdd(
                    when (producer) {
                        is QueryImpactProducerEvidence.Invocation ->
                            producer.site.storageBytes().saturatedAdd(producer.producer.invocation.storageBytes())
                        is QueryImpactProducerEvidence.SiteOnly -> producer.site.storageBytes()
                    }
                )
            }
        )
        .saturatedAdd(seeds.fold(0L) { bytes, site -> bytes.saturatedAdd(site.storageBytes()) })
        .saturatedAdd(representationModels.fold(0L) { bytes, model -> bytes.saturatedAdd(model.storageBytes()) })
        .saturatedAdd(boundaryModels.fold(0L) { bytes, model -> bytes.saturatedAdd(model.storageBytes()) })
        .saturatedAdd(
            readRejections.fold(0L) { bytes, rejected ->
                bytes
                    .saturatedAdd(RETAINED_STATE_BASE_BYTES)
                    .saturatedAdd(rejected.source.storageBytes())
                    .saturatedAdd(rejected.domain.canonical(rejected.source.enclosing).utf8UpperBound())
            }
        )
        .saturatedAdd(observations.fold(0L) { bytes, observation -> bytes.saturatedAdd(observation.storageBytes()) })
        .saturatedMultiply(DETACHED_EVIDENCE_STORAGE_MULTIPLIER)
        .saturatedAdd(paths.fold(0L) { bytes, path -> bytes.saturatedAdd(path.retainedStorageBytes()) })

internal fun BoundaryModel.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(reference.toString().utf8UpperBound())
        .saturatedAdd(source.storageBytes())
        .saturatedAdd(
            when (this) {
                is BoundaryModel.Continuation ->
                    target.storageBytes().saturatedAdd(assumptions.toString().utf8UpperBound())
                is BoundaryModel.Terminal -> RETAINED_STATE_BASE_BYTES
            }
        )

private fun ValueFlowStep.storageBytes(): Long =
    RETAINED_STATE_BASE_BYTES.saturatedAdd(source.storageBytes())
        .saturatedAdd(domain.subject.storageBytes())
        .saturatedAdd(domain.boundary.canonical(domain.subject).utf8UpperBound())
        .saturatedAdd(transfers.fold(0L) { bytes, transfer -> bytes.saturatedAdd(transfer.storageBytes()) })
        .saturatedAdd(obligations.fold(0L) { bytes, obligation -> bytes.saturatedAdd(obligation.site.storageBytes()) })
        .saturatedAdd(
            when (val position = domain.position) {
                RelationReadPosition.Start -> RETAINED_STATE_BASE_BYTES
                is RelationReadPosition.Resume ->
                    RETAINED_STATE_BASE_BYTES.saturatedAdd(position.continuation.providerState.retainedBytes)
                        .saturatedAdd(position.continuation.retainedLimitations.toString().utf8UpperBound())
            }
        )

/** Full detached checkpoint prefix charge, independent of any terminal claim. */
fun QueryImpactStep.retainedStorageBytes(): Long =
    storageBytes().saturatedMultiply(DETACHED_EVIDENCE_STORAGE_MULTIPLIER)

fun QueryImpactRepresentation.retainedStorageBytes(): Long =
    when (this) {
        QueryImpactRepresentation.NotModeled -> RETAINED_STATE_BASE_BYTES
        is QueryImpactRepresentation.Present -> evidence.storageBytes()
    }.saturatedMultiply(DETACHED_EVIDENCE_STORAGE_MULTIPLIER)

fun RepresentationRule.retainedStorageBytes(): Long =
    storageBytes().saturatedMultiply(DETACHED_EVIDENCE_STORAGE_MULTIPLIER)

fun BoundaryModel.retainedStorageBytes(): Long = storageBytes().saturatedMultiply(DETACHED_EVIDENCE_STORAGE_MULTIPLIER)

private fun QueryImpactExecutionStop.storageBytes(): Long =
    when (this) {
        is QueryImpactExecutionStop.Cycle ->
            producer
                .storageBytes()
                .saturatedAdd(
                    prefix.fold(RETAINED_STATE_BASE_BYTES) { bytes, step -> bytes.saturatedAdd(step.storageBytes()) }
                )
        is QueryImpactExecutionStop.CheckpointCapacity -> RETAINED_STATE_BASE_BYTES.saturatedAdd(source.storageBytes())
    }

private const val DETACHED_EVIDENCE_STORAGE_MULTIPLIER = 8L
