package io.github.amichne.kast.change.plan.intellij

import io.github.amichne.kast.change.contract.AddDeclarationIntent
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.plan.spi.AddDeclarationEvidenceResult
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanner
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningEvidenceSource
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningLimitation
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningRejection
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningResult

class IntellijAddDeclarationPlanner(
    private val evidenceSource: AddDeclarationPlanningEvidenceSource,
) : AddDeclarationPlanner {
    /**
     * Proof transition:
     * AddDeclarationIntent to AddDeclarationPlanningResult.
     *
     * A planned result establishes one canonical plan assembled only from detached IntelliJ
     * evidence for the exact same intent. AddDeclarationPlanningRejection is the closed expected
     * failure. All live IntelliJ extraction is confined to the injected evidence source.
     */
    override suspend fun plan(intent: AddDeclarationIntent): AddDeclarationPlanningResult =
        when (val result = evidenceSource.evidence(intent)) {
            is AddDeclarationEvidenceResult.Rejected ->
                AddDeclarationPlanningResult.Rejected(result.rejection)

            is AddDeclarationEvidenceResult.Proven -> {
                if (result.evidence.intent != intent) {
                    AddDeclarationPlanningResult.Rejected(
                        AddDeclarationPlanningRejection.of(
                            AddDeclarationPlanningLimitation.EVIDENCE_INTENT_MISMATCH,
                        ),
                    )
                } else {
                    AddDeclarationPlanningResult.Planned(
                        PlannedAddDeclaration.issue(result.evidence),
                    )
                }
            }
        }
}
