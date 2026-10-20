package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactBoundaryCompatibilityDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryConnectionDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRequiredDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactBranchCompletionDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactInterpolationScalarKindDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationApplicationDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationBranchDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationCurrentDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationHistoryDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationStateDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationUnknownDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactTryBranchAlternativeDocument
import io.github.amichne.kast.protocol.contract.ImpactUnguardedTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
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
import io.github.amichne.kast.relation.contract.ValueInterpolationScalarKind
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.relation.contract.ValueTryBranchAlternative

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

internal fun ValueTransfer.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactCompilerTransferDocument> =
    source
        .impactDocument(references)
        .impactZip(target.impactDocument(references))
        .impactZip(evidence.impactDocument(references))
        .impactMap { (sites, evidence) ->
            val (source, target) = sites
            ImpactCompilerTransferDocument(
                source,
                target,
                when (kind) {
                    ValueTransferKind.LOCAL_BINDING -> ImpactTransferKindDocument.LOCAL_BINDING
                    ValueTransferKind.LOCAL_READ -> ImpactTransferKindDocument.LOCAL_READ
                    ValueTransferKind.ARGUMENT -> ImpactTransferKindDocument.ARGUMENT
                    ValueTransferKind.RECEIVER -> ImpactTransferKindDocument.RECEIVER
                    ValueTransferKind.RETURN -> ImpactTransferKindDocument.RETURN
                    ValueTransferKind.PROPERTY_ASSIGNMENT -> ImpactTransferKindDocument.PROPERTY_ASSIGNMENT
                    ValueTransferKind.BRANCH_ALTERNATIVE -> ImpactTransferKindDocument.BRANCH_ALTERNATIVE
                    ValueTransferKind.DERIVATION -> ImpactTransferKindDocument.DERIVATION
                    ValueTransferKind.WRAPPER_RETURN -> ImpactTransferKindDocument.WRAPPER_RETURN
                    ValueTransferKind.SOURCE_CALL_ENTRY -> ImpactTransferKindDocument.SOURCE_CALL_ENTRY
                    ValueTransferKind.SOURCE_CALL_RETURN -> ImpactTransferKindDocument.SOURCE_CALL_RETURN
                    ValueTransferKind.FIELD_READ -> ImpactTransferKindDocument.FIELD_READ
                    ValueTransferKind.CONSTRUCTOR_FIELD -> ImpactTransferKindDocument.CONSTRUCTOR_FIELD
                },
                evidence,
            )
        }

private fun ValueTransferEvidence.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactTransferEvidenceDocument> =
    when (this) {
        is ValueTransferEvidence.Guarded ->
            delegate.impactDocument(references).impactZip(added.impactDocument(references)).impactThen {
                (delegate, added) ->
                if (delegate is ImpactUnguardedTransferEvidenceDocument)
                    Refinement.Refined(ImpactTransferEvidenceDocument.Guarded(delegate, added))
                else Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
            }
        ValueTransferEvidence.Direct -> Refinement.Refined(ImpactTransferEvidenceDocument.Direct)
        is ValueSourceTransfer -> operation.impactDocument(references).impactMap(ImpactTransferEvidenceDocument::Source)
        is ValueTransferEvidence.StringInterpolation ->
            templateRange.impactDocument().impactZip(entryRange.impactDocument()).impactThen { (template, entry) ->
                when (val index = ProtocolOffset.parse(entryIndex.value)) {
                    is Refinement.Refined ->
                        Refinement.Refined(
                            ImpactTransferEvidenceDocument.StringInterpolation(
                                template,
                                entry,
                                index.value,
                                scalarKind.impactDocument(),
                            )
                        )
                    is Refinement.Rejected ->
                        Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
                }
            }
        is ValueTransferEvidence.NormalBranchResult ->
            tryRange.impactDocument().impactZip(branchRange.impactDocument()).impactMap { (enclosing, branch) ->
                ImpactTransferEvidenceDocument.NormalBranchResult(
                    enclosing,
                    branch,
                    when (val branchAlternative = alternative) {
                        ValueTryBranchAlternative.TryBody -> ImpactTryBranchAlternativeDocument.TryBody
                        is ValueTryBranchAlternative.CatchBody ->
                            ImpactTryBranchAlternativeDocument.CatchBody(branchAlternative.index.value)
                    },
                    ImpactBranchCompletionDocument.NORMAL_COMPLETION,
                )
            }
    }

internal fun RepresentationModelApplication.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactRepresentationApplicationDocument> {
    val rule =
        when (this) {
            is RepresentationModelApplication.Transfer -> rule
            is RepresentationModelApplication.Transformation -> rule
        }
    return source
        .impactDocument(references)
        .impactZip(target.impactDocument(references))
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

internal fun BoundaryModel.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactBoundaryRuleDocument> =
    reference.rule.value.impactId().impactZip(source.impactDocument(references)).impactThen { (id, source) ->
        when (this) {
            is BoundaryModel.Continuation ->
                target.impactDocument(references).impactThen { target ->
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

internal fun BoundaryObligation.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactBoundaryObligationDocument> =
    position
        .impactDocument(references)
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

internal fun BoundaryArrival.Connected.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactBoundaryConnectionDocument> =
    model.reference
        .impactDocument()
        .impactZip(model.impactDocument(references))
        .impactZip(obligations.impactEach { it.impactDocument(references) })
        .impactMap { (model, obligations) ->
            ImpactBoundaryConnectionDocument(
                model.first,
                model.second as ImpactBoundaryRuleDocument.Continuation,
                obligations,
            )
        }

internal fun RepresentationEvidence.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactRepresentationEvidenceDocument.Present> =
    site
        .impactDocument(references)
        .impactZip(
            branches.impactEach { branch ->
                branch.current
                    .impactDocument()
                    .impactZip(branch.history.impactEach { it.impactDocument(references) })
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

private fun RepresentationHistory.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactRepresentationHistoryDocument> =
    when (this) {
        is RepresentationHistory.Origin ->
            reference
                .impactDocument()
                .impactZip(rule.impactDocument())
                .impactZip(output.impactDocument(references))
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
            transfer.impactDocument(references).impactMap(ImpactRepresentationHistoryDocument::CompilerTransfer)
        is RepresentationModelApplication ->
            impactDocument(references).impactMap(ImpactRepresentationHistoryDocument::ModeledApplication)
        is ValueModelApplication ->
            impactDocument(references).impactMap(ImpactRepresentationHistoryDocument::ModeledValue)
        is RepresentationHistory.Unmodeled ->
            source.impactDocument(references).impactZip(target.impactDocument(references)).impactMap { (source, target)
                ->
                ImpactRepresentationHistoryDocument.Unmodeled(source, target)
            }
        is RepresentationHistory.BoundaryModel ->
            reference
                .impactDocument()
                .impactZip(source.impactDocument(references))
                .impactZip(target.impactDocument(references))
                .impactMap { (source, target) ->
                    ImpactRepresentationHistoryDocument.BoundaryModel(source.first, source.second, target)
                }
    }

private fun ValueInterpolationScalarKind.impactDocument(): ImpactInterpolationScalarKindDocument =
    when (this) {
        ValueInterpolationScalarKind.STRING -> ImpactInterpolationScalarKindDocument.STRING
        ValueInterpolationScalarKind.BOOLEAN -> ImpactInterpolationScalarKindDocument.BOOLEAN
        ValueInterpolationScalarKind.CHAR -> ImpactInterpolationScalarKindDocument.CHAR
        ValueInterpolationScalarKind.BYTE -> ImpactInterpolationScalarKindDocument.BYTE
        ValueInterpolationScalarKind.SHORT -> ImpactInterpolationScalarKindDocument.SHORT
        ValueInterpolationScalarKind.INT -> ImpactInterpolationScalarKindDocument.INT
        ValueInterpolationScalarKind.LONG -> ImpactInterpolationScalarKindDocument.LONG
        ValueInterpolationScalarKind.FLOAT -> ImpactInterpolationScalarKindDocument.FLOAT
        ValueInterpolationScalarKind.DOUBLE -> ImpactInterpolationScalarKindDocument.DOUBLE
    }
