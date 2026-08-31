package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.diagnostic.service.DiagnosticService
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.service.RelationService
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
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.service.traversalOperations
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
    val relation: RelationOperations,
    val traversal: TraversalOperations,
    val diagnostic: DiagnosticOperations,
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
            val workspace = WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(published) }
            val relation = RelationService(
                workspace,
                RelationCompilerPort { request -> relationCompilation(request) },
            )
            val diagnostic = DiagnosticService(
                workspace,
                DiagnosticCompilerPort { scope -> diagnosticCompilation(scope) },
            )
            fixture = InstalledSymbolProtocolFixture(
                workspace,
                discovery,
                exact,
                relation,
                traversalOperations(relation),
                diagnostic,
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

        private fun batch(
            root: Path,
            request: SymbolDiscoveryRequest,
        ): SymbolDiscoveryBatch {
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
            val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
                location.file,
                location.offset.value,
                location.offset.value + 6,
                selected.candidate.name.value,
                "sample.Sample.sample",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function(
                    "sample.Sample.sample", null, emptyList(), emptyList(), 0,
                ).refinedFixture(),
            ).refinedFixture()
            return SymbolSelector.issue(selected, evidence).refinedFixture()
        }

        private fun relationCompilation(request: RelationRequest): RelationCompilation {
            val related = relatedEndpoint(request)
            val source: RelationEndpoint
            val target: RelationEndpoint
            if (request.meaning == RelationMeaning.Callees) {
                source = request.subject
                target = related
            } else {
                source = related
                target = request.subject
            }
            val occurrence = RelationOccurrence.fromBoundary(
                request.subject.file,
                request.subject.range.startInclusive,
                request.subject.range.endExclusive,
            ).refinedFixture()
            val fact = RelationFact.create(
                request,
                source,
                target,
                occurrence,
                RelationProvenance.K2_AUTHORED_SOURCE,
            ).refinedFixture()
            val batch = RelationBatch.create(
                request,
                listOf(fact),
                RelationByteCount.parse(
                    fact.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong(),
                ).refinedFixture(),
                RelationWorkCount.parse(1).refinedFixture(),
            ).refinedFixture()
            return RelationCompilation.complete(batch)
        }

        private fun relatedEndpoint(request: RelationRequest): RelationEndpoint.Resolved {
            val start = request.subject.range.endExclusive + 1
            val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
                request.subject.file,
                start,
                start + 7,
                request.subject.name.value + "Related",
                request.subject.name.value + ".Related",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function(
                    request.subject.name.value + ".Related",
                    null,
                    emptyList(),
                    listOf(request.subject.compilerIdentity.value),
                    0,
                ).refinedFixture(),
            ).refinedFixture()
            return RelationEndpoint.resolve(
                request.subject.lease,
                request.subject.scope,
                evidence,
            ).refinedFixture()
        }

        private fun diagnosticCompilation(scope: DiagnosticScope): DiagnosticCompilation {
            val fact = DiagnosticFact.fromBoundary(
                scope,
                scope.files.single(),
                0,
                6,
                DiagnosticSeverity.ERROR,
                "KAST001",
                "fixture diagnostic",
            ).refinedFixture()
            val batch = DiagnosticBatch.create(scope, listOf(fact)).refinedFixture()
            return DiagnosticCompilation.complete(batch)
        }
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedFixture(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected fixture rejection: $failure")
}
