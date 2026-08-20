package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.runtime.composition.protocol.CandidateSelectorIssuance
import io.github.amichne.kast.runtime.composition.protocol.CandidateSelectorLookup
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.ExactSelectorIssuance
import io.github.amichne.kast.runtime.composition.protocol.ExactSelectorLookup
import io.github.amichne.kast.runtime.composition.protocol.RelationEndpointIssuance
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
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
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CanonicalProtocolAuthorityDurabilityTest {
    @Test
    fun `candidate and exact selectors survive authority recreation`() {
        val fixture = fixture()
        val first = CanonicalProtocolAuthority()
        val candidateDocument = (first.issueCandidates(fixture.batch) as
            CandidateSelectorIssuance.Issued).selectors.single()
        val restoredCandidate = assertInstanceOf(
            CandidateSelectorLookup.Found::class.java,
            CanonicalProtocolAuthority().candidate(candidateDocument),
        ).selection
        assertEquals(fixture.selection.lease, restoredCandidate.lease)
        assertEquals(fixture.selection.scope, restoredCandidate.scope)
        assertEquals(fixture.selection.candidate, restoredCandidate.candidate)

        val exactDocument = (first.issueExact(fixture.selector) as
            ExactSelectorIssuance.Issued).selector
        val restoredExact = assertInstanceOf(
            ExactSelectorLookup.Found::class.java,
            CanonicalProtocolAuthority().exact(exactDocument),
        ).selector
        assertEquals(fixture.selector.lease, restoredExact.lease)
        assertEquals(fixture.selector.fingerprint, restoredExact.fingerprint)
    }

    @Test
    fun `relation targets issue exact selectors accepted by every exact consumer`() {
        val fixture = fixture()
        val endpoint = RelationEndpoint.resolve(
            fixture.selector.lease,
            fixture.selector.scope,
            fixture.relatedEvidence,
        ).refined()

        val document = (CanonicalProtocolAuthority().issueEndpoint(endpoint) as
            RelationEndpointIssuance.Issued).selector

        val restored = assertInstanceOf(
            ExactSelectorLookup.Found::class.java,
            CanonicalProtocolAuthority().exact(document),
        ).selector
        assertEquals(endpoint.file, restored.file)
        assertEquals(endpoint.range, restored.range)
        assertEquals(endpoint.compilerIdentity, fixture.relatedEvidence.compilerIdentity)
    }

    @Test
    fun `manufactured selector documents fail closed`() {
        val manufactured = ProtocolText.parse("exact:v1:not-valid").refined()
        assertEquals(ExactSelectorLookup.Missing, CanonicalProtocolAuthority().exact(manufactured))
    }

    private fun fixture(): Fixture {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refined())
        val scope = SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.INCLUDE,
        )
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(lease, scope),
            SymbolDiscoveryKind.SYMBOL,
            SymbolDiscoveryPattern.parse("handle").refined(),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(10).refined(),
                    WorkUnitLimit.parse(100).refined(),
                    ElapsedTimeLimitMillis.parse(1_000).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "handle",
            lease,
            Path.of("/workspace/src/Controller.kt"),
            "file:///workspace/src/Controller.kt",
            20,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            request,
            listOf(candidate),
            SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refined(),
            SymbolDiscoveryWorkCount.parse(1).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
            ),
        ).refined()
        val selection = SymbolDiscoverySelection.select(batch, 0).refined()
        val evidence = evidence(candidate, "sample.Controller.handle", "handle#function", 20)
        val selector = SymbolSelector.issue(selection, evidence).refined()
        val related = evidence(candidate, "sample.Service.call", "call#function", 80, "call")
        return Fixture(batch, selection, selector, related)
    }

    private fun evidence(
        candidate: SymbolDiscoveryCandidate,
        qualified: String,
        compiler: String,
        start: Int,
        name: String = candidate.name.value,
    ): CompilerGroundedSymbolEvidence = CompilerGroundedSymbolEvidence.fromBoundary(
        file = candidate.location.file,
        rawStartInclusive = start,
        rawEndExclusive = start + name.length,
        rawName = name,
        rawQualifiedIdentity = qualified,
        kind = CompilerSymbolKind.FUNCTION,
        compilerIdentity = CompilerSymbolIdentity.parse(compiler).refined(),
    ).refined()

    private data class Fixture(
        val batch: SymbolDiscoveryBatch,
        val selection: SymbolDiscoverySelection,
        val selector: SymbolSelector,
        val relatedEvidence: CompilerGroundedSymbolEvidence,
    )

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
