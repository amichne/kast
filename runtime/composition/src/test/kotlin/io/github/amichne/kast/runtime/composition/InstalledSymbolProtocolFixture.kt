package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.ResolvedSymbol
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path

internal class InstalledSymbolProtocolFixture private constructor(
    val workspace: WorkspaceInspectionOperations,
    val discovery: SymbolDiscoveryOperations,
    val exact: SymbolExactOperations,
) {
    var discoveryRequest: SymbolDiscoveryRequest? = null
        private set
    var resolutionRequest: SymbolResolutionRequest? = null
        private set
    var descriptionRequest: ExactSymbolRequest? = null
        private set

    companion object {
        fun create(root: Path): InstalledSymbolProtocolFixture {
            val published = published(root)
            lateinit var fixture: InstalledSymbolProtocolFixture
            val discovery = SymbolDiscoveryOperations { request ->
                fixture.discoveryRequest = request
                SymbolDiscoveryResult.Discovered(SymbolDiscoveryOutcome.Complete(batch(root, request)))
            }
            val exact = object : SymbolExactOperations {
                override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult {
                    fixture.resolutionRequest = request
                    return SymbolResolutionResult.Resolved(
                        ResolvedSymbol(selector(request)),
                    )
                }

                override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult {
                    fixture.descriptionRequest = request
                    return SymbolDescriptionResult.Described(SymbolDescription.from(request.selector))
                }
            }
            fixture = InstalledSymbolProtocolFixture(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(published) },
                discovery,
                exact,
            )
            return fixture
        }

        private fun published(root: Path): PublishedWorkspace {
            val canonical = CanonicalWorkspaceRoot.fromCanonicalPath(root).refinedFixture()
            val reconciled = ReconciledWorkspace.admit(
                WorkspaceCandidate(canonical, WorkspaceStateIdentity.parse("symbol-state").refinedFixture()),
                WorkspaceEvidenceKind.entries.toSet(),
            ).refinedFixture()
            return PublishedWorkspace.publish(
                reconciled,
                EvidenceGeneration.parse(11).refinedFixture(),
            )
        }

        private fun batch(root: Path, request: SymbolDiscoveryRequest): SymbolDiscoveryBatch {
            val candidate = SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.SYMBOL,
                "sample",
                request.scope.lease,
                root.resolve("src/main/kotlin/Sample.kt"),
                root.resolve("src/main/kotlin/Sample.kt").toUri().toString(),
                0,
            ).refinedFixture()
            return SymbolDiscoveryBatch.create(
                request,
                listOf(candidate),
                SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refinedFixture(),
                SymbolDiscoveryWorkCount.parse(1).refinedFixture(),
                SymbolDiscoveryTimings(
                    SymbolDiscoveryElapsedNanoseconds.parse(0).refinedFixture(),
                    SymbolDiscoveryElapsedNanoseconds.parse(0).refinedFixture(),
                ),
            ).refinedFixture()
        }

        private fun selector(request: SymbolResolutionRequest): SymbolSelector {
            val selected = request.selection
            val location = selected.candidate.location as
                io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation.Declaration
            val compilerIdentity = CompilerSymbolIdentity.parse("sample#function").refinedFixture()
            val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
                location.file,
                location.offset.value,
                location.offset.value + 6,
                selected.candidate.name.value,
                "sample.Sample.sample",
                CompilerSymbolKind.FUNCTION,
                compilerIdentity,
            ).refinedFixture()
            return SymbolSelector.issue(selected, evidence).refinedFixture()
        }
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedFixture(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected fixture rejection: $failure")
}
