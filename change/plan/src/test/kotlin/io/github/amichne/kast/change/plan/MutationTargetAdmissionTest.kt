package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.EditableMutationTarget
import io.github.amichne.kast.change.contract.MutationTargetAdmissionFailure
import io.github.amichne.kast.change.contract.MutationTargetObservation
import io.github.amichne.kast.change.contract.ObservedMutationTargetState
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class MutationTargetAdmissionTest {
    private val authoredRoot = sourceRoot(
        location = "app/src/main/kotlin",
        provenance = SourceRootProvenance.Authored,
    )

    @Test
    fun `every invalid target predicate has a distinct closed failure`() {
        val generated = sourceRoot(
            location = authoredRoot.location.value,
            provenance = SourceRootProvenance.Generated,
        )
        val unknown = sourceRoot(
            location = authoredRoot.location.value,
            provenance = SourceRootProvenance.Unknown(
                io.github.amichne.kast.workspace.contract.ProvenanceFailure.ExcludedFromSourceModel,
            ),
        )
        val nested = sourceRoot(
            location = "app/src/main",
            provenance = SourceRootProvenance.Authored,
        )
        val otherOwner = sourceRoot(
            location = "lib/src/main/kotlin",
            provenance = SourceRootProvenance.Authored,
            project = ":lib",
        ).owner
        val currentSelector = selector()
        val staleSelector = selector(generation = 6L)
        val externalSelector = selector(path = Path.of("/external/Service.kt"))
        val cases = listOf(
            observation(listOf(generated), currentSelector, generated.owner) to
                MutationTargetAdmissionFailure.GENERATED_SOURCE_ROOT,
            observation(listOf(unknown), currentSelector, unknown.owner) to
                MutationTargetAdmissionFailure.UNKNOWN_SOURCE_ROOT,
            observation(listOf(authoredRoot), externalSelector, authoredRoot.owner) to
                MutationTargetAdmissionFailure.ESCAPED_TARGET,
            observation(listOf(authoredRoot, nested), currentSelector, authoredRoot.owner) to
                MutationTargetAdmissionFailure.AMBIGUOUS_OWNERSHIP,
            observation(listOf(authoredRoot), staleSelector, authoredRoot.owner) to
                MutationTargetAdmissionFailure.STALE_STATE,
            observation(listOf(authoredRoot), currentSelector, otherOwner) to
                MutationTargetAdmissionFailure.WRONG_OWNER,
        )

        cases.forEach { (observation, expected) ->
            assertEquals(expected, EditableMutationTarget.admit(observation).rejected(), expected.name)
        }
        assertEquals(cases.size, cases.map { it.second }.distinct().size)
    }

    @Test
    fun `only admitted authored exact target capability enters later planning`() {
        val selector = selector()
        val observation = observation(listOf(authoredRoot), selector, authoredRoot.owner)

        val target = EditableMutationTarget.admit(observation).refined()

        assertEquals(selector, entersPlanning(target))
        assertEquals(selector.lease, target.lease)
        assertEquals(selector.file, target.file)
        assertEquals(selector.range, target.range)
        assertEquals(authoredRoot, target.sourceRoot)
        assertEquals(contentHash(), target.content)
        assertEquals(WorkspaceStateIdentity("state-7"), target.workspaceState)
    }

    private fun observation(
        roots: List<SourceRoot>,
        selector: SymbolSelector,
        expectedOwner: io.github.amichne.kast.workspace.contract.GradleSourceSetOwner,
    ): MutationTargetObservation = MutationTargetObservation(
        workspace = workspace(roots),
        selector = selector,
        expectedOwner = expectedOwner,
        observedState = ObservedMutationTargetState(
            lease = selector.lease,
            file = selector.file,
            content = contentHash(),
        ),
    )

    private fun workspace(roots: List<SourceRoot>): PublishedWorkspace = PublishedWorkspace.publish(
        ReconciledWorkspace.admit(
            WorkspaceCandidate(workspaceRoot(), WorkspaceStateIdentity("state-7")),
            WorkspaceEvidenceKind.entries.toSet(),
            roots,
        ).refined(),
        generation(7L),
    )

    private fun sourceRoot(
        location: String,
        provenance: SourceRootProvenance,
        project: String = ":app",
    ): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(
            ideaModuleName = project.removePrefix(":"),
            workspaceRelativeBuildRoot = ".",
            gradleProjectPath = project,
            sourceSetName = "main",
            workspaceRelativeSourceRoot = location,
            provenance = provenance,
        ),
    ).refined()

    private fun selector(
        generation: Long = 7L,
        path: Path = Path.of("/workspace/app/src/main/kotlin/sample/Service.kt"),
    ): SymbolSelector {
        val lease = io.github.amichne.kast.workspace.contract.SemanticReadLease(
            workspaceRoot(),
            generation(generation),
        )
        val scope = SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.INCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(lease, scope),
            SymbolDiscoveryKind.SYMBOL,
            SymbolDiscoveryPattern.parse("service").refined(),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(4L).refined(),
                    ElapsedTimeLimitMillis.parse(100L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "service",
            lease,
            path,
            "file://${path}",
            10,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            request,
            listOf(candidate),
            candidate.projectedUtf8Size(),
            SymbolDiscoveryWorkCount.parse(1L).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
        val selection = SymbolDiscoverySelection.select(batch, 0).refined()
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            location.file,
            location.offset.value,
            location.offset.value + 7,
            "service",
            "sample.Service.service",
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolIdentity.parse("function|sample.Service.service").refined(),
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun entersPlanning(target: EditableMutationTarget): SymbolSelector = target.selector

    private fun workspaceRoot(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun generation(value: Long): EvidenceGeneration = EvidenceGeneration.parse(value).refined()

    private fun contentHash(): WorkspaceSourceContentHash =
        WorkspaceSourceContentHash.parse("a".repeat(64)).refined()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error("expected rejection")
        is Refinement.Rejected -> failure
    }
}
