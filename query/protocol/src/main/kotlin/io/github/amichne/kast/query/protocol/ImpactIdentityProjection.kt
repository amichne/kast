package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactBoundaryContractDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactCallablePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactCallbackSelectionDocument
import io.github.amichne.kast.protocol.contract.ImpactContentViewDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
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
import io.github.amichne.kast.relation.contract.ValueDeclarationIdentity
import io.github.amichne.kast.relation.contract.ValueExecutionIdentity
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
    owner
        .impactDocument(basis)
        .impactZip(range.impactDocument())
        .impactZip(role.impactDocument())
        .impactZip(execution.impactDocument())
        .impactZip(valuePath.impactDocument())
        .impactZip(callStack.impactDocument())
        .impactZip(conditions.impactDocument())
        .impactMap { (site, conditions) ->
            val (state, stack) = site
            val (core, path) = state
            val (position, execution) = core
            val (location, role) = position
            ImpactValueSiteReferenceDocument(location.first, location.second, role, execution, path, stack, conditions)
        }
        .impactZip(localVersion.impactDocument())
        .impactMap { (site, version) -> site.copy(localVersion = version) }

internal fun ValueSite.impactDocument(): ImpactProjected<ImpactValueSiteReferenceDocument> = identity.impactDocument()

internal fun ValueInvocation.impactDocument(): ImpactProjected<ImpactInvocationReferenceDocument> =
    range
        .impactDocument()
        .impactZip(callable.impactDeclaration())
        .impactZip(executionOwner.identity.impactDocument())
        .impactMap { (invocation, execution) ->
            ImpactInvocationReferenceDocument(invocation.first, invocation.second, execution)
        }

internal fun ValueDeclarationIdentity.impactDocument(
    basis: SemanticReadIdentity
): ImpactProjected<ImpactDeclarationReferenceDocument> =
    basis
        .impactDocument()
        .impactZip(file.stableValue.impactText())
        .impactZip(range.impactDocument())
        .impactZip(compiler.value.impactText())
        .impactMap { (declaration, compiler) ->
            ImpactDeclarationReferenceDocument(
                declaration.first.first,
                declaration.first.second,
                declaration.second,
                compiler,
            )
        }

internal fun ValueExecutionIdentity.impactDocument(): ImpactProjected<ImpactExecutionOwnerDocument> =
    when (val execution = context) {
        io.github.amichne.kast.relation.contract.ValueExecutionContext.Declaration ->
            Refinement.Refined(ImpactExecutionOwnerDocument.Declaration)
        is io.github.amichne.kast.relation.contract.ValueExecutionContext.ReviewedCallback ->
            execution.binding.impactDocument().impactMap(ImpactExecutionOwnerDocument::ReviewedCallback)
        is io.github.amichne.kast.relation.contract.ValueExecutionContext.DeclaredCallback ->
            execution.binding.impactDocument().impactMap(ImpactExecutionOwnerDocument::DeclaredCallback)
        is io.github.amichne.kast.relation.contract.ValueExecutionContext.Callback ->
            execution.body
                .impactCaptureBody()
                .impactZip(execution.invocations.impactEach { it.impactCapture() })
                .impactZip(execution.selectedInvocation.impactCapture())
                .impactMap { (context, selected) ->
                    val (body, invocations) = context
                    val selection =
                        if (execution.invocations.count { it.body == execution.body } == 1)
                            ImpactCallbackSelectionDocument.Unique
                        else ImpactCallbackSelectionDocument.Exact(selected)
                    ImpactExecutionOwnerDocument.Callback(body, invocations, selection)
                }
    }

internal fun io.github.amichne.kast.relation.contract.RelationCallableBody.impactCaptureBody():
    ImpactProjected<io.github.amichne.kast.protocol.contract.ImpactCapturedBodyDocument> =
    file.stableValue
        .impactText()
        .impactZip(range.impactDocument())
        .impactZip(compilerIdentity.value.impactText())
        .impactMap { (body, compiler) ->
            io.github.amichne.kast.protocol.contract.ImpactCapturedBodyDocument(body.first, body.second, compiler)
        }

internal fun io.github.amichne.kast.relation.contract.ValueCaptureInvocation.impactCapture():
    ImpactProjected<io.github.amichne.kast.protocol.contract.ImpactCaptureInvocationDocument> =
    body
        .impactCaptureBody()
        .impactZip(supply.invocation.enclosing.impactDeclaration())
        .impactZip(supply.invocation.impactDocument())
        .impactZip(supply.invocationOwner.impactCaptureBody())
        .impactZip(supply.parameter.range.impactDocument())
        .impactZip(supply.position.value.impactOffset())
        .impactZip(invocation.occurrence.range.impactDocument())
        .impactZip(invocation.owner.impactCaptureBody())
        .impactMap { (route, owner) ->
            val (binding, occurrence) = route
            val (parameter, position) = binding
            val (supplying, parameterRange) = parameter
            val (call, supplyOwner) = supplying
            val (bodyEnclosing, supply) = call
            io.github.amichne.kast.protocol.contract.ImpactCaptureInvocationDocument(
                bodyEnclosing.first,
                bodyEnclosing.second,
                supply,
                supplyOwner,
                parameterRange,
                position,
                occurrence,
                owner,
            )
        }
        .impactZip(supply.frame.target.impactDocument())
        .impactZip(invocation.forwardings.impactEach { it.impactForwarding() })
        .impactMap { (capture, forwardings) ->
            capture.first.copy(supplyTarget = capture.second, forwardings = forwardings)
        }

internal fun ValueRole.impactDocument(): ImpactProjected<ImpactValueRoleDocument> =
    when (this) {
        is ValueRole.StringConversionResult ->
            invocation.impactDocument().impactMap(ImpactValueRoleDocument::StringConversionResult)
        ValueRole.ExpressionResult -> Refinement.Refined(ImpactValueRoleDocument.ExpressionResult)
        is ValueRole.LoopResult -> result.impactDocument().impactMap(ImpactValueRoleDocument::LoopResult)
        ValueRole.LocalBinding -> Refinement.Refined(ImpactValueRoleDocument.LocalBinding)
        ValueRole.LocalRead -> Refinement.Refined(ImpactValueRoleDocument.LocalRead)
        ValueRole.Return -> Refinement.Refined(ImpactValueRoleDocument.Return)
        ValueRole.PropertyAssignment -> Refinement.Refined(ImpactValueRoleDocument.PropertyAssignment)
        is ValueRole.Argument ->
            call.impactDocument().impactZip(position.value.impactOffset()).impactMap { (call, position) ->
                ImpactValueRoleDocument.Argument(call, position)
            }
        is ValueRole.Receiver ->
            call.impactDocument().impactMap { invocation ->
                ImpactValueRoleDocument.Receiver(invocation, kind.impactDocument())
            }
        is ValueRole.Formal -> position.impactDocument().impactMap(ImpactValueRoleDocument::Formal)
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
