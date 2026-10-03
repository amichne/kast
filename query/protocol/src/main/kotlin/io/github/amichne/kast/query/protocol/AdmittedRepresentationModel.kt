package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedImpactModelSyntax
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentifierDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationRuleDocument
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RepresentationState
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import java.util.Collections

/** All rule positions are bound to supplied fresh compiler witnesses; no lookup or semantic execution occurs here. */
class AdmittedRepresentationModel
private constructor(val domain: RepresentationDomain, val rules: List<RepresentationRule>) {
    companion object {
        fun admit(
            syntax: AdmittedImpactModelSyntax,
            current: List<RevalidatedRelationEndpoint>,
        ): Refinement<AdmittedRepresentationModel, ImpactModelAdmissionFailure> {
            val document = syntax.document
            if (document !is ImpactModelDocument.Representation)
                return Refinement.Rejected(ImpactModelAdmissionFailure.MODEL_KIND_MISMATCH)
            val identity =
                when (val admitted = document.model.domainIdentity()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val states = mutableListOf<ModelIdentifier>()
            for (state in document.states.values) when (val admitted = state.domainIdentifier()) {
                is Refinement.Refined -> states += admitted.value
                is Refinement.Rejected -> return admitted
            }
            val domain =
                when (val admitted = RepresentationDomain.admit(identity, states)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ImpactModelAdmissionFailure.Domain(admitted.failure))
                }
            val rules = mutableListOf<RepresentationRule>()
            for (rule in document.rules.values) when (val admitted = admitRule(identity, domain, rule, current)) {
                is Refinement.Refined -> rules += admitted.value
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(AdmittedRepresentationModel(domain, Collections.unmodifiableList(rules.toList())))
        }
    }
}

private fun admitRule(
    model: ContractModelIdentity,
    domain: RepresentationDomain,
    syntax: ImpactRepresentationRuleDocument,
    current: List<RevalidatedRelationEndpoint>,
): Refinement<RepresentationRule, ImpactModelAdmissionFailure> {
    val id =
        when (val admitted = syntax.id.domainIdentifier()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val reference = ModelRuleReference(model, id)
    return when (syntax) {
        is ImpactRepresentationRuleDocument.Origin -> syntax.bindOrigin(reference, domain, current)
        is ImpactRepresentationRuleDocument.Transfer -> syntax.bindTransfer(reference, current)
        is ImpactRepresentationRuleDocument.Transformation -> syntax.bindTransformation(reference, domain, current)
        is ImpactRepresentationRuleDocument.ConsumerExpectation -> syntax.bindConsumer(reference, domain, current)
    }
}

private fun ImpactModelIdentifierDocument.bindState(
    domain: RepresentationDomain
): Refinement<RepresentationState, ImpactModelAdmissionFailure> {
    val id =
        when (val admitted = domainIdentifier()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return when (val admitted = domain.state(id)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(ImpactModelAdmissionFailure.State(admitted.failure))
    }
}

private fun ImpactRepresentationRuleDocument.Origin.bindOrigin(
    reference: ModelRuleReference,
    domain: RepresentationDomain,
    current: List<RevalidatedRelationEndpoint>,
): Refinement<RepresentationRule, ImpactModelAdmissionFailure> {
    val output =
        when (val admitted = output.bind(current)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val state =
        when (val admitted = state.bindState(domain)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return RepresentationRule.Origin.admit(reference, output, state).admittedRule()
}

private fun ImpactRepresentationRuleDocument.Transfer.bindTransfer(
    reference: ModelRuleReference,
    current: List<RevalidatedRelationEndpoint>,
): Refinement<RepresentationRule, ImpactModelAdmissionFailure> {
    val input =
        when (val admitted = input.bind(current)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val output =
        when (val admitted = output.bind(current)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return RepresentationRule.Transfer.admit(reference, input, output).admittedRule()
}

private fun ImpactRepresentationRuleDocument.Transformation.bindTransformation(
    reference: ModelRuleReference,
    domain: RepresentationDomain,
    current: List<RevalidatedRelationEndpoint>,
): Refinement<RepresentationRule, ImpactModelAdmissionFailure> {
    val input =
        when (val admitted = input.bind(current)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val output =
        when (val admitted = output.bind(current)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val from =
        when (val admitted = from.bindState(domain)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val to =
        when (val admitted = to.bindState(domain)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return RepresentationRule.Transformation.admit(reference, input, output, from, to).admittedRule()
}

private fun ImpactRepresentationRuleDocument.ConsumerExpectation.bindConsumer(
    reference: ModelRuleReference,
    domain: RepresentationDomain,
    current: List<RevalidatedRelationEndpoint>,
): Refinement<RepresentationRule, ImpactModelAdmissionFailure> {
    val input =
        when (val admitted = input.bind(current)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val state =
        when (val admitted = state.bindState(domain)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return RepresentationRule.ConsumerExpectation.admit(reference, input, state).admittedRule()
}

private fun <T> Refinement<T, io.github.amichne.kast.relation.contract.RepresentationRuleFailure>.admittedRule():
    Refinement<T, ImpactModelAdmissionFailure> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> Refinement.Rejected(ImpactModelAdmissionFailure.Rule(failure))
    }
