package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactBoundaryContractDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactCallablePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactContentViewDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactModelFormatDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentityDocument
import io.github.amichne.kast.protocol.contract.ImpactModelValuePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactRuleReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryPositionReference
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

internal fun SemanticReadIdentity.impactDocument(): ImpactProjected<ImpactSemanticBasisDocument> =
    when (this) {
        is SemanticReadIdentity.Published ->
            workspaceRoot.value.impactText().impactZip(lease.generation.value.impactRevision()).impactMap {
                (root, generation) ->
                ImpactSemanticBasisDocument.Published(root, generation)
            }
        is SemanticReadIdentity.Live ->
            workspaceRoot.value
                .impactText()
                .impactZip(reference.host.value.toString().impactText())
                .impactZip(reference.epoch.value.impactRevision())
                .impactThen { (rootHost, epoch) ->
                    ImpactModelFormatDocument.parse(reference.version)
                        .impactFailure(ImpactPathProjectionFailure::Model)
                        .impactMap { version ->
                            ImpactSemanticBasisDocument.Live(
                                rootHost.first,
                                rootHost.second,
                                epoch,
                                ImpactContentViewDocument.SAVED_PSI_COMMITTED,
                                version,
                            )
                        }
                }
    }

internal fun ExactDeclarationTextRange.impactDocument(): ImpactProjected<ImpactSourceRangeDocument> =
    startInclusive.impactOffset().impactZip(endExclusive.impactOffset()).impactMap { (start, end) ->
        ImpactSourceRangeDocument(start, end)
    }

internal fun RelationEndpoint.impactDeclaration(): ImpactProjected<ImpactDeclarationReferenceDocument> =
    lease.identity
        .impactDocument()
        .impactZip(file.stableValue.impactText())
        .impactZip(range.impactDocument())
        .impactZip(compilerIdentity.value.impactText())
        .impactMap { (declaration, compiler) ->
            ImpactDeclarationReferenceDocument(
                declaration.first.first,
                declaration.first.second,
                declaration.second,
                compiler,
            )
        }

internal fun ValueSiteIdentity.impactDocument(): ImpactProjected<ImpactValueSiteReferenceDocument> =
    basis
        .impactDocument()
        .impactZip(owner.file.stableValue.impactText())
        .impactZip(owner.range.impactDocument())
        .impactZip(owner.compiler.value.impactText())
        .impactThen { (declaration, compiler) ->
            val enclosing =
                ImpactDeclarationReferenceDocument(
                    declaration.first.first,
                    declaration.first.second,
                    declaration.second,
                    compiler,
                )
            range.impactDocument().impactZip(role.impactDocument()).impactMap { (range, role) ->
                ImpactValueSiteReferenceDocument(enclosing, range, role)
            }
        }

internal fun ValueSite.impactDocument(): ImpactProjected<ImpactValueSiteReferenceDocument> = identity.impactDocument()

internal fun ValueInvocation.impactDocument(): ImpactProjected<ImpactInvocationReferenceDocument> =
    range.impactDocument().impactZip(callable.impactDeclaration()).impactMap { (range, callable) ->
        ImpactInvocationReferenceDocument(range, callable)
    }

private fun ValueRole.impactDocument(): ImpactProjected<ImpactValueRoleDocument> =
    when (this) {
        ValueRole.ExpressionResult -> Refinement.Refined(ImpactValueRoleDocument.ExpressionResult)
        ValueRole.LocalBinding -> Refinement.Refined(ImpactValueRoleDocument.LocalBinding)
        ValueRole.LocalRead -> Refinement.Refined(ImpactValueRoleDocument.LocalRead)
        ValueRole.Return -> Refinement.Refined(ImpactValueRoleDocument.Return)
        ValueRole.PropertyAssignment -> Refinement.Refined(ImpactValueRoleDocument.PropertyAssignment)
        is ValueRole.Argument ->
            call.impactDocument().impactZip(position.value.impactOffset()).impactMap { (call, position) ->
                ImpactValueRoleDocument.Argument(call, position)
            }
    }

internal fun ContractModelIdentity.impactDocument(): ImpactProjected<ImpactModelIdentityDocument> =
    id.value.impactId().impactZip(version.value.impactVersion()).impactZip(provenance.value.impactId()).impactMap {
        (model, provenance) ->
        ImpactModelIdentityDocument(model.first, model.second, provenance)
    }

internal fun ModelRuleReference.impactDocument(): ImpactProjected<ImpactRuleReferenceDocument> =
    model.impactDocument().impactZip(rule.value.impactId()).impactMap { (model, rule) ->
        ImpactRuleReferenceDocument(model, rule)
    }

internal fun ExactModelCallablePosition.impactDocument(): ImpactProjected<ImpactCallablePositionDocument> =
    endpoint
        .impactDeclaration()
        .impactZip(
            when (val position = position) {
                ModelValuePosition.Result -> Refinement.Refined(ImpactModelValuePositionDocument.Result)
                is ModelValuePosition.Argument ->
                    position.position.value.impactOffset().impactMap(ImpactModelValuePositionDocument::Argument)
            }
        )
        .impactMap { (declaration, position) -> ImpactCallablePositionDocument(declaration, position) }

internal fun BoundaryPosition.impactDocument(): ImpactProjected<ImpactBoundaryPositionDocument> =
    reference.impactDocument()

internal fun BoundaryPositionReference.impactDocument(): ImpactProjected<ImpactBoundaryPositionDocument> =
    site
        .impactDocument()
        .impactZip(contract.id.value.impactId())
        .impactZip(contract.version.value.impactVersion())
        .impactZip(slot.value.impactId())
        .impactMap { (boundary, slot) ->
            ImpactBoundaryPositionDocument(
                boundary.first.first,
                when (kind) {
                    BoundaryKind.SERIALIZATION -> ImpactBoundaryKindDocument.SERIALIZATION
                    BoundaryKind.PERSISTENCE -> ImpactBoundaryKindDocument.PERSISTENCE
                    BoundaryKind.EXTERNAL_SYSTEM -> ImpactBoundaryKindDocument.EXTERNAL_SYSTEM
                },
                ImpactBoundaryContractDocument(boundary.first.second, boundary.second),
                slot,
            )
        }
