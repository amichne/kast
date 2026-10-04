package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactBoundaryCompatibilityDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryConnectionDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRequiredDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationApplicationDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationBranchDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationCurrentDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationHistoryDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationStateDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationUnknownDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryCompatibilityAssumption
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryObligation
import io.github.amichne.kast.relation.contract.BoundaryRequiredEvidence
import io.github.amichne.kast.relation.contract.BoundaryTerminalMeaning
import io.github.amichne.kast.relation.contract.RepresentationCurrent
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationModelApplication
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RepresentationUnknownReason
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind

internal fun RepresentationRule.impactDocument(): ImpactProjected<ImpactRepresentationRuleDocument> =
    reference.rule.value.impactId().impactThen { id ->
        when (this) {
            is RepresentationRule.Origin ->
                output.impactDocument().impactZip(state.id.value.impactId()).impactMap { (output, state) ->
                    ImpactRepresentationRuleDocument.Origin(id, output, state)
                }
            is RepresentationRule.Transfer ->
                input.impactDocument().impactZip(output.impactDocument()).impactMap { (input, output) ->
                    ImpactRepresentationRuleDocument.Transfer(id, input, output)
                }
            is RepresentationRule.Transformation ->
                input
                    .impactDocument()
                    .impactZip(output.impactDocument())
                    .impactZip(from.id.value.impactId())
                    .impactZip(to.id.value.impactId())
                    .impactMap { (rule, to) ->
                        ImpactRepresentationRuleDocument.Transformation(
                            id,
                            rule.first.first,
                            rule.first.second,
                            rule.second,
                            to,
                        )
                    }
            is RepresentationRule.ConsumerExpectation ->
                input.impactDocument().impactZip(expected.id.value.impactId()).impactMap { (input, expected) ->
                    ImpactRepresentationRuleDocument.ConsumerExpectation(id, input, expected)
                }
        }
    }

internal fun ValueTransfer.impactDocument(): ImpactProjected<ImpactCompilerTransferDocument> =
    source.impactDocument().impactZip(target.impactDocument()).impactMap { (source, target) ->
        ImpactCompilerTransferDocument(
            source,
            target,
            when (kind) {
                ValueTransferKind.LOCAL_BINDING -> ImpactTransferKindDocument.LOCAL_BINDING
                ValueTransferKind.LOCAL_READ -> ImpactTransferKindDocument.LOCAL_READ
                ValueTransferKind.ARGUMENT -> ImpactTransferKindDocument.ARGUMENT
                ValueTransferKind.RETURN -> ImpactTransferKindDocument.RETURN
                ValueTransferKind.PROPERTY_ASSIGNMENT -> ImpactTransferKindDocument.PROPERTY_ASSIGNMENT
                ValueTransferKind.BRANCH_ALTERNATIVE -> ImpactTransferKindDocument.BRANCH_ALTERNATIVE
                ValueTransferKind.WRAPPER_RETURN -> ImpactTransferKindDocument.WRAPPER_RETURN
            },
        )
    }

internal fun RepresentationModelApplication.impactDocument(): ImpactProjected<ImpactRepresentationApplicationDocument> {
    val rule =
        when (this) {
            is RepresentationModelApplication.Transfer -> rule
            is RepresentationModelApplication.Transformation -> rule
        }
    return source
        .impactDocument()
        .impactZip(target.impactDocument())
        .impactZip(invocation.impactDocument())
        .impactZip(reference.impactDocument())
        .impactZip(rule.impactDocument())
        .impactMap { (application, rule) ->
            ImpactRepresentationApplicationDocument(
                application.first.first.first,
                application.first.first.second,
                application.first.second,
                application.second,
                rule,
            )
        }
}

internal fun BoundaryModel.impactDocument(): ImpactProjected<ImpactBoundaryRuleDocument> =
    reference.rule.value.impactId().impactZip(source.impactDocument()).impactThen { (id, source) ->
        when (this) {
            is BoundaryModel.Continuation ->
                target.impactDocument().impactThen { target ->
                    assumptions
                        .sortedBy { it.ordinal }
                        .map {
                            when (it) {
                                BoundaryCompatibilityAssumption.CONTRACT_COMPATIBLE ->
                                    ImpactBoundaryCompatibilityDocument.CONTRACT_COMPATIBLE
                                BoundaryCompatibilityAssumption.REPRESENTATION_PRESERVED ->
                                    ImpactBoundaryCompatibilityDocument.REPRESENTATION_PRESERVED
                            }
                        }
                        .impactBounded()
                        .impactMap { ImpactBoundaryRuleDocument.Continuation(id, source, target, it) }
                }
            is BoundaryModel.Terminal ->
                Refinement.Refined(
                    ImpactBoundaryRuleDocument.Terminal(
                        id,
                        source,
                        meaning.impactDocument(),
                    )
                )
        }
    }

internal fun BoundaryTerminalMeaning.impactDocument(): ImpactBoundaryTerminalDocument =
    when (this) {
        BoundaryTerminalMeaning.REVIEWED_DISPOSAL -> ImpactBoundaryTerminalDocument.REVIEWED_DISPOSAL
        BoundaryTerminalMeaning.REVIEWED_EXTERNAL_SINK -> ImpactBoundaryTerminalDocument.REVIEWED_EXTERNAL_SINK
        BoundaryTerminalMeaning.REVIEWED_RETENTION -> ImpactBoundaryTerminalDocument.REVIEWED_RETENTION
    }

internal fun BoundaryObligation.impactDocument(): ImpactProjected<ImpactBoundaryObligationDocument> =
    position
        .impactDocument()
        .impactZip(
            required
                .sortedBy { it.ordinal }
                .map {
                    when (it) {
                        BoundaryRequiredEvidence.REVIEWED_MODEL -> ImpactBoundaryRequiredDocument.REVIEWED_MODEL
                        BoundaryRequiredEvidence.EXACT_DOWNSTREAM_POSITION ->
                            ImpactBoundaryRequiredDocument.EXACT_DOWNSTREAM_POSITION
                        BoundaryRequiredEvidence.CORRECTED_MODEL -> ImpactBoundaryRequiredDocument.CORRECTED_MODEL
                        BoundaryRequiredEvidence.CURRENT_BASIS_REVALIDATION ->
                            ImpactBoundaryRequiredDocument.CURRENT_BASIS_REVALIDATION
                        BoundaryRequiredEvidence.RETENTION_POLICY -> ImpactBoundaryRequiredDocument.RETENTION_POLICY
                        BoundaryRequiredEvidence.DECODING_COMPATIBILITY ->
                            ImpactBoundaryRequiredDocument.DECODING_COMPATIBILITY
                        BoundaryRequiredEvidence.MIGRATION_PROOF -> ImpactBoundaryRequiredDocument.MIGRATION_PROOF
                    }
                }
                .impactBounded()
        )
        .impactMap { (position, required) -> ImpactBoundaryObligationDocument(position, required) }

internal fun BoundaryArrival.Connected.impactDocument(): ImpactProjected<ImpactBoundaryConnectionDocument> =
    model.reference
        .impactDocument()
        .impactZip(model.impactDocument())
        .impactZip(obligations.impactEach { it.impactDocument() })
        .impactMap { (model, obligations) ->
            ImpactBoundaryConnectionDocument(
                model.first,
                model.second as ImpactBoundaryRuleDocument.Continuation,
                obligations,
            )
        }

internal fun RepresentationEvidence.impactDocument(): ImpactProjected<ImpactRepresentationEvidenceDocument.Present> =
    site
        .impactDocument()
        .impactZip(
            branches.impactEach { branch ->
                branch.current
                    .impactDocument()
                    .impactZip(branch.history.impactEach { it.impactDocument() })
                    .impactMap { (current, history) -> ImpactRepresentationBranchDocument(current, history) }
            }
        )
        .impactMap { (site, branches) -> ImpactRepresentationEvidenceDocument.Present(site, branches) }

internal fun RepresentationCurrent.impactDocument(): ImpactProjected<ImpactRepresentationCurrentDocument> =
    when (this) {
        is RepresentationCurrent.Known -> state.impactDocument().impactMap(ImpactRepresentationCurrentDocument::Known)
        is RepresentationCurrent.Unknown ->
            Refinement.Refined(
                ImpactRepresentationCurrentDocument.Unknown(
                    when (reason) {
                        RepresentationUnknownReason.UNMODELED_TRANSFORMATION ->
                            ImpactRepresentationUnknownDocument.UNMODELED_TRANSFORMATION
                        RepresentationUnknownReason.INPUT_STATE_NOT_ESTABLISHED ->
                            ImpactRepresentationUnknownDocument.INPUT_STATE_NOT_ESTABLISHED
                        RepresentationUnknownReason.BOUNDARY_PRESERVATION_UNPROVEN ->
                            ImpactRepresentationUnknownDocument.BOUNDARY_PRESERVATION_UNPROVEN
                    }
                )
            )
    }

internal fun io.github.amichne.kast.relation.contract.RepresentationState.impactDocument():
    ImpactProjected<ImpactRepresentationStateDocument> =
    domain.model
        .impactDocument()
        .impactZip(domain.states.impactEach { it.value.impactId() })
        .impactZip(id.value.impactId())
        .impactMap { (domain, state) -> ImpactRepresentationStateDocument(domain.first, domain.second, state) }

private fun RepresentationHistory.impactDocument(): ImpactProjected<ImpactRepresentationHistoryDocument> =
    when (this) {
        is RepresentationHistory.Origin ->
            reference
                .impactDocument()
                .impactZip(rule.impactDocument())
                .impactZip(output.impactDocument())
                .impactZip(invocation.impactDocument())
                .impactMap { (origin, invocation) ->
                    ImpactRepresentationHistoryDocument.Origin(
                        origin.first.first,
                        origin.first.second as ImpactRepresentationRuleDocument.Origin,
                        origin.second,
                        invocation,
                    )
                }
        is RepresentationHistory.CompilerTransfer ->
            transfer.impactDocument().impactMap(ImpactRepresentationHistoryDocument::CompilerTransfer)
        is RepresentationModelApplication ->
            impactDocument().impactMap(ImpactRepresentationHistoryDocument::ModeledApplication)
        is RepresentationHistory.Unmodeled ->
            source.impactDocument().impactZip(target.impactDocument()).impactMap { (source, target) ->
                ImpactRepresentationHistoryDocument.Unmodeled(source, target)
            }
        is RepresentationHistory.BoundaryModel ->
            reference
                .impactDocument()
                .impactZip(source.impactDocument())
                .impactZip(target.impactDocument())
                .impactMap { (source, target) ->
                    ImpactRepresentationHistoryDocument.BoundaryModel(source.first, source.second, target)
                }
    }
