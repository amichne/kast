package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.contract.AddFilePlanRequest
import io.github.amichne.kast.change.contract.AddFileTargetObservation
import io.github.amichne.kast.change.contract.CreatableKotlinFileTarget
import io.github.amichne.kast.change.contract.KotlinFileSourceText
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path

internal class InstalledChangeProtocolFixture private constructor(
    val workspace: WorkspaceInspectionOperations,
    val addFile: AddFilePlanRequest,
) {
    companion object {
        fun create(root: Path): InstalledChangeProtocolFixture {
            val canonical = CanonicalWorkspaceRoot.fromCanonicalPath(root).fixtureRefined()
            val sourceRoot = SourceRoot.admit(
                GradleSourceRootEvidence(
                    "app",
                    ".",
                    ":app",
                    "main",
                    "src/main/kotlin",
                    SourceRootProvenance.Authored,
                ),
            ).fixtureRefined()
            val reconciled = ReconciledWorkspace.admit(
                WorkspaceCandidate(canonical, WorkspaceStateIdentity.parse("change-state").fixtureRefined()),
                WorkspaceEvidenceKind.entries.toSet(),
                listOf(sourceRoot),
            ).fixtureRefined()
            val published = PublishedWorkspace.publish(
                reconciled,
                EvidenceGeneration.parse(13).fixtureRefined(),
            )
            val path = root.resolve("src/main/kotlin/sample/Added.kt")
            val file = SymbolDiscoveryFileIdentity.fromBoundary(
                canonical,
                path,
                path.toUri().toString(),
            ).fixtureRefined() as SymbolDiscoveryFileIdentity.Workspace
            val target = CreatableKotlinFileTarget.admit(
                AddFileTargetObservation(published, file, sourceRoot.owner),
            ).fixtureRefined()
            return InstalledChangeProtocolFixture(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(published) },
                AddFilePlanRequest(
                    target,
                    KotlinFileSourceText.parse("package sample\n\nclass Added\n").fixtureRefined(),
                ),
            )
        }
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.fixtureRefined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected change fixture rejection: $failure")
}
