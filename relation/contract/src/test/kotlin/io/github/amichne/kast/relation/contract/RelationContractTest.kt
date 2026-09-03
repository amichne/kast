package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
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

class RelationContractTest {
    @Test
    fun `related endpoint preserves the exact compiler identity fingerprint`() {
        val selector = selector()
        val description = SymbolDescription.from(selector)
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            selector.file,
            selector.range.startInclusive,
            selector.range.endExclusive,
            selector.name.value,
            "sample.Subject.run",
            selector.kind,
            description.signature,
        ).refined()

        val endpoint = RelationEndpoint.resolve(
            selector.lease,
            selector.scope,
            evidence,
        ).refined()

        assertEquals(selector.fingerprint.value, endpoint.fingerprint.value)
    }

    @Test
    fun `related endpoint rejects changed compiler identity during revalidation`() {
        val selector = selector()
        val subject = RelationEndpoint.subject(selector)
        val endpoint = related(subject)
        val changed = CompilerGroundedSymbolEvidence.fromBoundary(
            endpoint.file,
            endpoint.range.startInclusive,
            endpoint.range.endExclusive,
            endpoint.name.value,
            "sample.Related.run",
            endpoint.kind,
            CanonicalCompilerSignature.function(
                "sample.Related.run",
                null,
                emptyList(),
                emptyList(),
                1,
            ).refined(),
        ).refined()

        val result = RevalidatedRelationEndpoint.validate(endpoint, changed)

        assertEquals(
            RelationEndpointRevalidationFailure.DECLARATION_MOVED_OR_CHANGED,
            (result as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `closed meaning fixes subject orientation`() {
        val request = request(RelationMeaning.Callees)
        val subject = request.subject
        val occurrence = RelationOccurrence.fromBoundary(request.subject.file, 41, 44).refined()

        val rejected = RelationFact.create(
            request,
            source = related(request.subject),
            target = subject,
            occurrence = occurrence,
            provenance = RelationProvenance.K2_AUTHORED_SOURCE,
        )

        assertEquals(
            RelationFactFailure.SUBJECT_ORIENTATION_MISMATCH,
            (rejected as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `terminal incomplete page has no continuation while resumable page stays bound`() {
        val request = request(RelationMeaning.References)
        val batch = RelationBatch.create(
            request,
            emptyList(),
            RelationByteCount.parse(0L).refined(),
            RelationWorkCount.parse(0L).refined(),
            RelationResultCount.parse(0).refined(),
        ).refined()
        val terminal = RelationCompilation.qualifiedTerminal(
            batch,
            setOf(RelationLimitation.UNRESOLVED_TARGET),
        ).refined()
        val cursor = RelationProviderCursor.start(RelationProviderKind.INTELLIJ_REFERENCES_V1)
            .advance(RelationProviderItemDescriptor.parse("first").refined())
        val resumable = RelationCompilation.qualifiedResumable(
            batch,
            setOf(RelationLimitation.RESULT_LIMIT_REACHED),
            cursor,
        ).refined()

        assertInstanceOf(RelationIncompleteCoverage.TerminalIncomplete::class.java, terminal.coverage)
        val resumableCoverage = assertInstanceOf(
            RelationIncompleteCoverage.Resumable::class.java,
            resumable.coverage,
        )
        assertEquals(0, terminal.coverage.knownMinimum.value)
        assertEquals(0, batch.resultCount.value)
        assertEquals(
            RelationResumeFailure.MEANING_MISMATCH,
            (
                RelationRequest.resume(
                    (request.subject as RelationEndpoint.Subject).selector,
                    RelationMeaning.Callers,
                    request.budget,
                    resumableCoverage.continuation,
                ) as Refinement.Rejected
            ).failure,
        )
    }

    @Test
    fun `resumable page rejects a cursor that cannot make forward progress`() {
        val request = request(RelationMeaning.References)
        val batch = RelationBatch.create(
            request,
            emptyList(),
            RelationByteCount.parse(0L).refined(),
            RelationWorkCount.parse(0L).refined(),
            RelationResultCount.parse(0).refined(),
        ).refined()

        val result = RelationCompilation.qualifiedResumable(
            batch,
            setOf(RelationLimitation.TIME_LIMIT_REACHED),
            request.providerCursor,
        )

        assertEquals(
            RelationIncompleteCoverageFailure.CURSOR_NOT_ADVANCED,
            (result as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `provider cursor commits order into its consumed prefix`() {
        val first = RelationProviderItemDescriptor.parse("first").refined()
        val second = RelationProviderItemDescriptor.parse("second").refined()

        val forward = RelationProviderCursor.start(RelationProviderKind.INTELLIJ_REFERENCES_V1)
            .advance(first)
            .advance(second)
        val moved = RelationProviderCursor.start(RelationProviderKind.INTELLIJ_REFERENCES_V1)
            .advance(second)
            .advance(first)

        assertEquals(2L, forward.nextPosition.value)
        org.junit.jupiter.api.Assertions.assertNotEquals(
            forward.consumedPrefixDigest,
            moved.consumedPrefixDigest,
        )
    }

    private fun related(subject: RelationEndpoint): RelationEndpoint.Resolved =
        RelationEndpoint.resolve(
            subject.lease,
            subject.scope,
            CompilerGroundedSymbolEvidence.fromBoundary(
                subject.file,
                71,
                82,
                "related",
                "sample.Related.run",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function(
                    "sample.Related.run",
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                ).refined(),
            ).refined(),
        ).refined()

    private fun request(meaning: RelationMeaning): RelationRequest = RelationRequest.start(
        selector(),
        meaning,
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(8).refined(),
                WorkUnitLimit.parse(32L).refined(),
                ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            RelationByteLimit.parse(100_000L).refined(),
        ),
    )

    private fun selector(): SymbolSelector {
        val lease = SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(19L).refined(),
        )
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("run").refined(),
                SymbolDiscoveryMatch.FUZZY,
            ),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(8L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "run",
            lease,
            Path.of("/workspace/src/Subject.kt"),
            "file:///workspace/src/Subject.kt",
            41,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            request,
            listOf(candidate),
            SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refined(),
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
            location.offset.value + 10,
            selection.candidate.name.value,
            "sample.Subject.run",
            CompilerSymbolKind.FUNCTION,
            CanonicalCompilerSignature.function(
                "sample.Subject.run",
                null,
                emptyList(),
                emptyList(),
                0,
            ).refined(),
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
