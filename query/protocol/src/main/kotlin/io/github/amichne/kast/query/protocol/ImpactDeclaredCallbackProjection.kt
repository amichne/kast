package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCallbackDependencyContractDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackReceiverDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackResultEffectDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackValueBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyContractProvenanceDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.relation.contract.CallbackDependencyContract
import io.github.amichne.kast.relation.contract.CallbackDependencyContractProvenance
import io.github.amichne.kast.relation.contract.DeclaredCallbackResultEffect
import io.github.amichne.kast.relation.contract.DeclaredCallbackValueBinding
import io.github.amichne.kast.relation.contract.ValueDeclarationIdentity
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

internal fun DeclaredCallbackValueBinding.impactDocument():
    ImpactProjected<ImpactDeclaredCallbackValueBindingDocument> =
    enclosing.impactDeclaration().impactZip(body.impactCaptureBody()).impactZip(contract.impactDocument()).impactMap {
        (owner, contract) ->
        ImpactDeclaredCallbackValueBindingDocument(
            owner.first,
            owner.second,
            contract,
            when (resultEffect) {
                DeclaredCallbackResultEffect.RETURNS_RESULT_OF ->
                    ImpactDeclaredCallbackResultEffectDocument.RETURNS_RESULT_OF
                DeclaredCallbackResultEffect.UNSPECIFIED -> ImpactDeclaredCallbackResultEffectDocument.UNSPECIFIED
            },
        )
    }

private fun CallbackDependencyContract.impactDocument(): ImpactProjected<ImpactCallbackDependencyContractDocument> =
    basis
        .impactDocument()
        .impactZip(occurrence.file.stableValue.impactText())
        .impactZip(occurrence.range.impactDocument())
        .impactZip(owner.impactCaptureBody())
        .impactZip(targetDocument())
        .impactZip(position.value.impactOffset())
        .impactZip(classDigest.value.impactText())
        .impactMap { (selected, digest) ->
            val (supply, position) = selected
            val (location, target) = supply
            val (occurrence, owner) = location
            ImpactCallbackDependencyContractDocument(
                occurrence.first.first,
                occurrence.first.second,
                occurrence.second,
                owner,
                target,
                position,
                digest,
                when (provenance) {
                    CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT ->
                        QueryCallbackDependencyContractProvenanceDocument.KOTLIN_BINARY_CONTRACT
                },
                invocationKind.protocolCallbackDocument(),
            )
        }

private fun CallbackDependencyContract.targetDocument(): ImpactProjected<ImpactDeclaredCallbackTargetDocument> =
    target.impactCallableTarget(basis)

internal fun CompilerGroundedSymbolEvidence.impactCallableTarget(
    basis: SemanticReadIdentity
): ImpactProjected<ImpactDeclaredCallbackTargetDocument> {
    val signature = this.signature
    if (signature !is CanonicalCompilerSignature.Function)
        return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return ValueDeclarationIdentity.fromCompiler(this)
        .impactDocument(basis)
        .impactZip(signature.qualifiedIdentity.value.impactText())
        .impactZip(signature.receiver.declaredReceiverDocument())
        .impactZip(signature.contextReceivers.impactEach { it.value.impactText() })
        .impactZip(signature.valueParameters.impactEach { it.value.impactText() })
        .impactZip(
            QueryDiscoveryCountDocument.parse(signature.typeParameterCount.value.toLong())
                .impactFailure(ImpactPathProjectionFailure::Count)
        )
        .impactMap { (parameters, count) ->
            val (contexts, values) = parameters
            val (callable, receivers) = contexts
            val (identity, receiver) = callable
            ImpactDeclaredCallbackTargetDocument(identity.first, identity.second, receiver, receivers, values, count)
        }
}

internal fun CanonicalCompilerReceiver.declaredReceiverDocument():
    ImpactProjected<ImpactDeclaredCallbackReceiverDocument> =
    when (this) {
        CanonicalCompilerReceiver.Absent -> Refinement.Refined(ImpactDeclaredCallbackReceiverDocument.Absent)
        is CanonicalCompilerReceiver.Present ->
            type.value.impactText().impactMap(ImpactDeclaredCallbackReceiverDocument::Present)
    }
