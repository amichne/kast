package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.ExistingDeclarationSourceText
import io.github.amichne.kast.change.contract.ReplacementDeclarationSourceText
import io.github.amichne.kast.change.contract.ReplaceDeclarationChangePlan
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanRequest
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanResult
import io.github.amichne.kast.change.contract.ReplaceDeclarationTarget
import io.github.amichne.kast.change.plan.PureReplaceDeclarationPlanningService
import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

internal fun VerifiedMutationFixture.replaceDeclarationPlan(): ReplaceDeclarationChangePlan {
    val current = sourceText.substring(
        plan.target.range.startInclusive,
        plan.target.range.endExclusive,
    )
    val target = ReplaceDeclarationTarget.admit(
        plan.target,
        ExistingDeclarationSourceText.parse(current).refined(),
    ).refined()
    val evidence = plan.evidence
    val result = PureReplaceDeclarationPlanningService().plan(
        ReplaceDeclarationPlanRequest(
            target,
            ReplacementDeclarationSourceText.parse("fun service(): Int = 1").refined(),
            AddDeclarationPlanningEvidenceInput(
                evidence.relations,
                evidence.traversals,
                evidence.diagnostics,
            ),
        ),
    )
    return (result as ReplaceDeclarationPlanResult.Planned).plan
}

internal fun VerifiedMutationFixture.replaceDeclarationEvidence(
    applied: AppliedUnverified,
    observedSource: String = "fun service(): Int = 1",
): ReplaceDeclarationVerificationEvidence {
    val scope = DiagnosticScope.fromCanonicalPaths(
        resultingWorkspace.readLease,
        listOf(java.nio.file.Path.of(applied.source.path.value)),
    ).refined()
    val complete = DiagnosticCompilation.complete(DiagnosticBatch.empty(scope))
    return ReplaceDeclarationVerificationEvidence(
        applied.source,
        applied.postimage,
        listOf(DiagnosticCheckResult.Complete(complete.batch, complete.coverage)),
        ObservedReplaceDeclarationDelta.fromCompilerBoundary(
            observedSource,
            ExactDeclarationTextRange.parse(
                plan.target.range.startInclusive,
                plan.target.range.startInclusive + observedSource.length,
            ).refined(),
            1,
        ).refined(),
    )
}
