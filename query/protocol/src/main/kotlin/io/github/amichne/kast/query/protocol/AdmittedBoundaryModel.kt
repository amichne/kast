package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedImpactModelSyntax
import io.github.amichne.kast.protocol.contract.ImpactBoundaryCompatibilityDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.relation.contract.BoundaryCompatibilityAssumption
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryTerminalMeaning
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelRuleReference
import java.util.Collections

/** Exact source and downstream sites retain their own bases. Reviewed contracts remain modeled evidence. */
class AdmittedBoundaryModel private constructor(val model: ContractModelIdentity, val rules: List<BoundaryModel>) {
    companion object {
        fun admit(
            syntax: AdmittedImpactModelSyntax,
            current: List<BoundaryPosition>,
        ): Refinement<AdmittedBoundaryModel, ImpactModelAdmissionFailure> {
            val document = syntax.document
            if (document !is ImpactModelDocument.Boundary)
                return Refinement.Rejected(ImpactModelAdmissionFailure.MODEL_KIND_MISMATCH)
            val model =
                when (val admitted = document.model.domainIdentity()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val rules = mutableListOf<BoundaryModel>()
            for (rule in document.rules.values) when (val admitted = admitBoundaryRule(model, rule, current)) {
                is Refinement.Refined -> rules += admitted.value
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(AdmittedBoundaryModel(model, Collections.unmodifiableList(rules.toList())))
        }
    }
}

private fun admitBoundaryRule(
    model: ContractModelIdentity,
    syntax: ImpactBoundaryRuleDocument,
    current: List<BoundaryPosition>,
): Refinement<BoundaryModel, ImpactModelAdmissionFailure> {
    val id =
        when (val admitted = syntax.id.domainIdentifier()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val reference = ModelRuleReference(model, id)
    val admission =
        when (syntax) {
            is ImpactBoundaryRuleDocument.Continuation -> {
                val source =
                    when (val admitted = syntax.source.bind(current)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return admitted
                    }
                val target =
                    when (val admitted = syntax.target.bind(current)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return admitted
                    }
                BoundaryModel.Continuation.admit(
                    reference,
                    source.reference,
                    target.reference,
                    source,
                    target,
                    syntax.assumptions.values.map { it.domainAssumption() }.toSet(),
                )
            }
            is ImpactBoundaryRuleDocument.Terminal -> {
                val source =
                    when (val admitted = syntax.source.bind(current)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return admitted
                    }
                BoundaryModel.Terminal.admit(reference, source.reference, source, syntax.meaning.domainMeaning())
            }
        }
    return when (admission) {
        is Refinement.Refined -> admission
        is Refinement.Rejected -> Refinement.Rejected(ImpactModelAdmissionFailure.Boundary(admission.failure))
    }
}

private fun ImpactBoundaryCompatibilityDocument.domainAssumption(): BoundaryCompatibilityAssumption =
    when (this) {
        ImpactBoundaryCompatibilityDocument.CONTRACT_COMPATIBLE -> BoundaryCompatibilityAssumption.CONTRACT_COMPATIBLE
        ImpactBoundaryCompatibilityDocument.REPRESENTATION_PRESERVED ->
            BoundaryCompatibilityAssumption.REPRESENTATION_PRESERVED
    }

private fun ImpactBoundaryTerminalDocument.domainMeaning(): BoundaryTerminalMeaning =
    when (this) {
        ImpactBoundaryTerminalDocument.REVIEWED_DISPOSAL -> BoundaryTerminalMeaning.REVIEWED_DISPOSAL
        ImpactBoundaryTerminalDocument.REVIEWED_EXTERNAL_SINK -> BoundaryTerminalMeaning.REVIEWED_EXTERNAL_SINK
        ImpactBoundaryTerminalDocument.REVIEWED_RETENTION -> BoundaryTerminalMeaning.REVIEWED_RETENTION
    }
