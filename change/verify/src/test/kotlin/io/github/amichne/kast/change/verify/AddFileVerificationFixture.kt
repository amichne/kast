package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.apply.ObservedAbsentMutationSource
import io.github.amichne.kast.change.apply.ObservedMutationPrecondition
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddFileChangePlan
import io.github.amichne.kast.change.contract.AddFilePlanRequest
import io.github.amichne.kast.change.contract.AddFilePlanResult
import io.github.amichne.kast.change.contract.AddFileTargetObservation
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.contract.CreatableKotlinFileTarget
import io.github.amichne.kast.change.contract.KotlinFileSourceText
import io.github.amichne.kast.change.contract.RenameSymbolChangePlan
import io.github.amichne.kast.change.contract.ReplaceDeclarationChangePlan
import io.github.amichne.kast.change.plan.PureAddFilePlanningService
import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import java.nio.file.Path

private val ADDED_FILE_PATH = Path.of("/workspace/app/src/main/kotlin/sample/Added.kt")

internal fun VerifiedMutationFixture.observedPrecondition(
    plan: ChangePlan = this.plan,
): ObservedMutationPrecondition = when (plan) {
    is AddFileChangePlan -> ObservedAbsentMutationSource.fromPhysicalBoundary(
        plan.target.file,
        SourceWriteAccess.Writable,
    )
    is AddDeclarationChangePlan,
    is RenameSymbolChangePlan,
    is ReplaceDeclarationChangePlan,
        -> observedSource(plan)
}

internal fun VerifiedMutationFixture.addFilePlan(): AddFileChangePlan {
    val file = SymbolDiscoveryFileIdentity.fromBoundary(
        workspace.root,
        ADDED_FILE_PATH,
        "file://$ADDED_FILE_PATH",
    ).refined() as SymbolDiscoveryFileIdentity.Workspace
    val target = CreatableKotlinFileTarget.admit(
        AddFileTargetObservation(workspace, file, plan.target.sourceRoot.owner),
    ).refined()
    val result = PureAddFilePlanningService().plan(
        AddFilePlanRequest(
            target,
            KotlinFileSourceText.parse("package sample\n\nclass Added\n").refined(),
        ),
    )
    return (result as AddFilePlanResult.Planned).plan
}

internal fun VerifiedMutationFixture.addFileEvidence(
    applied: AppliedUnverified,
): AddFileVerificationEvidence {
    val scope = DiagnosticScope.fromCanonicalPaths(
        resultingWorkspace.readLease,
        listOf(ADDED_FILE_PATH),
    ).refined()
    val complete = DiagnosticCompilation.complete(DiagnosticBatch.empty(scope))
    return AddFileVerificationEvidence(
        applied.source,
        applied.postimage,
        listOf(DiagnosticCheckResult.Complete(complete.batch, complete.coverage)),
        ObservedAddFileDelta.fromCompilerBoundary(applied.source, 1).refined(),
    )
}
