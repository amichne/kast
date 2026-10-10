package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RelationReferenceIdentityTest {
    @Test
    fun `repeated accounting preserves every ownership bound and charges both retained counts`() {
        for (meaning in listOf(RelationMeaning.References, RelationMeaning.TypeUses)) {
            val request = request(meaning)
            val target =
                RelationConfirmedReferenceTarget.fromCompiler(
                        request.subject,
                        CompilerGroundedSymbolEvidence.fromSelector(
                            (request.subject as RelationEndpoint.Subject).selector
                        ),
                    )
                    .refined()
            val cases = expectedAccountingBounds(request)
            for (expected in cases) {
                val occurrence = RelationOccurrence.fromBoundary(request.subject.file, 73, 79).refined()
                val value =
                    RelationReferenceOccurrence.confirmed(
                            request,
                            target,
                            occurrence,
                            if (expected.ownership is RelationReferenceOwnership.FileScoped)
                                RelationReferenceContext.IMPORT
                            else RelationReferenceContext.CODE,
                            expected.ownership,
                            RelationProvenance.K2_AUTHORED_SOURCE,
                        )
                        .refined()
                val identity = value.canonicalProjection()
                repeat(32) {
                    assertEquals(expected.retention, value.retainedBytes)
                    assertEquals(expected.projection, value.projectedUtf8Size())
                    assertEquals(identity, value.canonicalProjection())
                }
                assertSame(request.subject, value.target)
                assertSame(expected.ownership, value.ownership)
                assertSame(occurrence, value.occurrence)
                assertEquals(request.subject.lease.identity, value.authority)
                assertEquals(RelationFactCoverage.EXACT_COMPILER_CONFIRMED, value.coverage)
            }
        }
    }

    @Test
    fun `same compiler signature from another exact declaration is not the selected target`() {
        val subject = request(RelationMeaning.References).subject
        val otherFile = nativeFile(subject.lease, subject.name.value, Path.of("/workspace/other/PaymentService.kt"))
        val qualified =
            (subject.qualifiedIdentity
                    as io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Available)
                .value
        val duplicate =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    otherFile,
                    subject.range.startInclusive,
                    subject.range.endExclusive,
                    subject.name.value,
                    qualified,
                    subject.kind,
                    subject.signature,
                )
                .refined()
        assertEquals(subject.compilerIdentity, duplicate.compilerIdentity)
        assertEquals(
            Refinement.Rejected(RelationReferenceTargetFailure.DIFFERENT_DECLARATION_FILE),
            RelationConfirmedReferenceTarget.fromCompiler(subject, duplicate),
        )
        val moved =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    subject.file,
                    subject.range.startInclusive + 1,
                    subject.range.endExclusive + 1,
                    subject.name.value,
                    qualified,
                    subject.kind,
                    subject.signature,
                )
                .refined()
        assertEquals(
            Refinement.Rejected(RelationReferenceTargetFailure.DIFFERENT_DECLARATION_RANGE),
            RelationConfirmedReferenceTarget.fromCompiler(subject, moved),
        )
        val same = CompilerGroundedSymbolEvidence.fromSelector((subject as RelationEndpoint.Subject).selector)
        val confirmed = RelationConfirmedReferenceTarget.fromCompiler(subject, same).refined()
        assertSame(subject, confirmed.target)
        assertSame(same, confirmed.resolved)
    }

    @Test
    fun `class construction owner proof rejects identical class IDs in distinct module files`() {
        // Distinct source identities are starting facts; native module membership is qualified separately.
        val base = request(RelationMeaning.Callers).subject
        fun classEvidence(module: String): CompilerGroundedSymbolEvidence {
            val path = Path.of("/workspace/$module/src/Duplicate.kt")
            val file =
                (SymbolDiscoveryCandidate.fromBoundary(
                            SymbolDiscoveryKind.CLASS,
                            "Duplicate",
                            base.lease,
                            path,
                            path.toUri().toString(),
                            3,
                        )
                        .refined()
                        .location as SymbolDiscoveryCandidateLocation.Declaration)
                    .file
            return CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    3,
                    45,
                    "Duplicate",
                    "sample.Duplicate",
                    CompilerSymbolKind.CLASSLIKE,
                    CanonicalCompilerSignature.classLike("sample.Duplicate").refined(),
                )
                .refined()
        }
        val selected = classEvidence("module-a")
        val other = classEvidence("module-b")
        val endpoint = RelationEndpoint.resolve(base.lease, base.scope, selected).refined()
        assertEquals(selected.compilerIdentity, other.compilerIdentity)
        assertEquals(selected.signature, other.signature)
        assertEquals(
            Refinement.Rejected(RelationReferenceTargetFailure.DIFFERENT_DECLARATION_FILE),
            RelationConfirmedReferenceTarget.fromCompiler(endpoint, other),
        )
        val same = RelationConfirmedReferenceTarget.fromCompiler(endpoint, selected).refined()
        assertSame(selected, same.resolved)
        assertSame(endpoint, same.target)
    }

    @Test
    fun `compiler confirmed alias occurrence has file ownership and cannot manufacture a declaration edge`() {
        val request = request(RelationMeaning.References)
        val target =
            RelationConfirmedReferenceTarget.fromCompiler(
                    request.subject,
                    io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromSelector(
                        (request.subject as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject).selector
                    ),
                )
                .refined()
        val location = RelationOccurrence.fromBoundary(request.subject.file, 8, 14).refined()
        val occurrence =
            RelationReferenceOccurrence.confirmed(
                    request,
                    target,
                    location,
                    RelationReferenceContext.ALIASED_IMPORT,
                    RelationReferenceOwnership.FileScoped(RelationReferenceContext.ALIASED_IMPORT),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()

        assertSame(request.subject, occurrence.target)
        assertEquals(request.subject.lease.identity, occurrence.authority)
        assertEquals(RelationFactCoverage.EXACT_COMPILER_CONFIRMED, occurrence.coverage)
        assertEquals(
            RelationReferenceProjectionFailure.FILE_SCOPED,
            (occurrence.declarationFact(request) as Refinement.Rejected).failure,
        )
        assertEquals(
            RelationReferenceTargetFailure.DIFFERENT_COMPILER_IDENTITY,
            (RelationConfirmedReferenceTarget.fromCompiler(request.subject, related(request.subject).evidence)
                    as Refinement.Rejected)
                .failure,
        )
        assertEquals(
            RelationReferenceOccurrenceFailure.FILE_CONTEXT_MISMATCH,
            (RelationReferenceOccurrence.confirmed(
                    request,
                    target,
                    location,
                    RelationReferenceContext.CODE,
                    RelationReferenceOwnership.FileScoped(RelationReferenceContext.ALIASED_IMPORT),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                ) as Refinement.Rejected)
                .failure,
        )
    }

    @Test
    fun `owned reference carries the original endpoint proof into a real declaration edge`() {
        val request = request(RelationMeaning.References)
        val owner = related(request.subject)
        val target =
            RelationConfirmedReferenceTarget.fromCompiler(
                    request.subject,
                    io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromSelector(
                        (request.subject as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject).selector
                    ),
                )
                .refined()
        val location = RelationOccurrence.fromBoundary(owner.file, 73, 79).refined()
        val occurrence =
            RelationReferenceOccurrence.confirmed(
                    request,
                    target,
                    location,
                    RelationReferenceContext.TYPE,
                    RelationReferenceOwnership.DeclarationOwned(owner),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        val edge = occurrence.declarationFact(request).refined()

        assertSame(owner, edge.source)
        assertSame(request.subject, edge.target)
        assertSame(location, edge.occurrence)
    }
}

private data class ExpectedBounds(
    val ownership: RelationReferenceOwnership,
    val retention: Long,
    val projection: Long,
)

private fun expectedAccountingBounds(request: RelationRequest): List<ExpectedBounds> {
    // Independent pre-change bounds for these exact admitted fixtures, plus two retained Longs.
    return when (request.meaning) {
        RelationMeaning.References ->
            listOf(
                ExpectedBounds(
                    RelationReferenceOwnership.FileScoped(RelationReferenceContext.IMPORT),
                    1934L,
                    5289L,
                ),
                ExpectedBounds(
                    RelationReferenceOwnership.DeclarationOwned(related(request.subject)),
                    2666L,
                    5918L,
                ),
                ExpectedBounds(
                    RelationReferenceOwnership.Unavailable(RelationOwnershipUnavailableCause.UNRESOLVED_DECLARATION),
                    1976L,
                    5310L,
                ),
            )
        RelationMeaning.TypeUses ->
            listOf(
                ExpectedBounds(
                    RelationReferenceOwnership.FileScoped(RelationReferenceContext.IMPORT),
                    1930L,
                    5287L,
                ),
                ExpectedBounds(
                    RelationReferenceOwnership.DeclarationOwned(related(request.subject)),
                    2662L,
                    5916L,
                ),
                ExpectedBounds(
                    RelationReferenceOwnership.Unavailable(RelationOwnershipUnavailableCause.UNRESOLVED_DECLARATION),
                    1972L,
                    5308L,
                ),
            )
        else -> error("Unexpected reference meaning")
    }
}

private fun nativeFile(
    lease: io.github.amichne.kast.workspace.contract.SemanticReadAuthority,
    name: String,
    path: Path,
) =
    (SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.SYMBOL,
                name,
                lease,
                path,
                "file://$path",
                0,
            )
            .refined()
            .location as SymbolDiscoveryCandidateLocation.Declaration)
        .file

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
                        )
                        .refined(),
                )
                .refined(),
        )
        .refined()

private fun request(meaning: RelationMeaning): RelationRequest =
    RelationRequest.start(
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

private fun referenceDiscoveryRequest(lease: SemanticReadLease, exactFile: Boolean): SymbolDiscoveryRequest {
    return SymbolDiscoveryRequest(
        SymbolSearchScopeRequest(
            lease,
            if (exactFile)
                SymbolSearchScope.ExactFile(
                    io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath.fromCanonicalPath(
                            lease.workspaceRoot,
                            Path.of("/workspace/src/Subject.kt"),
                        )
                        .refined(),
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                )
            else
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
}

private fun referenceReadLease(): SemanticReadLease =
    SemanticReadLease(
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
        EvidenceGeneration.parse(19L).refined(),
    )

private fun selector(exactFile: Boolean = false): SymbolSelector {
    val lease = referenceReadLease()
    val request = referenceDiscoveryRequest(lease, exactFile)
    val candidate =
        SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.SYMBOL,
                "run",
                lease,
                Path.of("/workspace/src/Subject.kt"),
                "file:///workspace/src/Subject.kt",
                41,
            )
            .refined()
    val batch =
        SymbolDiscoveryBatch.create(
                request,
                listOf(candidate),
                SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refined(),
                SymbolDiscoveryWorkCount.parse(1L).refined(),
                SymbolDiscoveryTimings(
                    SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                    SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                ),
            )
            .refined()
    val selection = SymbolDiscoverySelection.select(batch, 0).refined()
    val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
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
                    )
                    .refined(),
            )
            .refined()
    return SymbolSelector.issue(selection, evidence).refined()
}

private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
