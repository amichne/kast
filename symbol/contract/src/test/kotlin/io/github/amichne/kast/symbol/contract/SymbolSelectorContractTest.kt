package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SymbolSelectorContractTest {
    @Test
    fun `compiler identity parsing fails closed`() {
        assertEquals(
            CompilerSymbolIdentityFailure.BLANK,
            CompilerSymbolIdentity.parse(" ").rejected(),
        )
        assertEquals(
            CompilerSymbolIdentityFailure.CONTROL_CHARACTER,
            CompilerSymbolIdentity.parse("sample.Service.call\n").rejected(),
        )
        assertEquals(
            CompilerSymbolIdentityFailure.TOO_LONG,
            CompilerSymbolIdentity.parse("x".repeat(4097)).rejected(),
        )
    }

    @Test
    fun `compiler identity is sealed into exact selection and revalidation`() {
        val selection = selection()
        val firstEvidence = evidence(selection, "sample.Service.call(kotlin.Int)")
        val secondEvidence = evidence(selection, "sample.Service.call(kotlin.String)")
        val first = SymbolSelector.issue(selection, firstEvidence).refined()
        val second = SymbolSelector.issue(selection, secondEvidence).refined()

        assertNotEquals(first.fingerprint, second.fingerprint)
        assertEquals(
            first,
            RevalidatedSymbolSelector.validate(first, firstEvidence).refined().selector,
        )
        assertEquals(
            SymbolSelectorRevalidationFailure.DECLARATION_MOVED_OR_CHANGED,
            RevalidatedSymbolSelector.validate(first, secondEvidence).rejected(),
        )
    }

    @Test
    fun `compiler evidence cannot escape the selected file name or offset`() {
        val selection = selection()
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val compilerIdentity = CompilerSymbolIdentity.parse("sample.Service.call(kotlin.Int)").refined()
        val otherFile = SymbolDiscoveryFileIdentity.fromBoundary(
            selection.lease.workspaceRoot,
            Path.of("/workspace/src/Other.kt"),
            "file:///workspace/src/Other.kt",
        ).refined()

        assertEquals(
            SymbolSelectorIssueFailure.FILE_MISMATCH,
            SymbolSelector.issue(
                selection,
                CompilerGroundedSymbolEvidence.fromBoundary(
                    otherFile,
                    location.offset.value,
                    location.offset.value + 10,
                    selection.candidate.name.value,
                    "sample.Service.call",
                    CompilerSymbolKind.FUNCTION,
                    compilerIdentity,
                ).refined(),
            ).rejected(),
        )
        assertEquals(
            SymbolSelectorIssueFailure.NAME_MISMATCH,
            SymbolSelector.issue(
                selection,
                CompilerGroundedSymbolEvidence.fromBoundary(
                    location.file,
                    location.offset.value,
                    location.offset.value + 10,
                    "other",
                    "sample.Service.other",
                    CompilerSymbolKind.FUNCTION,
                    compilerIdentity,
                ).refined(),
            ).rejected(),
        )
        assertEquals(
            SymbolSelectorIssueFailure.START_OFFSET_MISMATCH,
            SymbolSelector.issue(
                selection,
                CompilerGroundedSymbolEvidence.fromBoundary(
                    location.file,
                    location.offset.value + 1,
                    location.offset.value + 10,
                    selection.candidate.name.value,
                    "sample.Service.call",
                    CompilerSymbolKind.FUNCTION,
                    compilerIdentity,
                ).refined(),
            ).rejected(),
        )
    }

    private fun evidence(
        selection: SymbolDiscoverySelection,
        identity: String,
    ): CompilerGroundedSymbolEvidence {
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        return CompilerGroundedSymbolEvidence.fromBoundary(
            location.file,
            location.offset.value,
            location.offset.value + 10,
            selection.candidate.name.value,
            "sample.Service.call",
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolIdentity.parse(identity).refined(),
        ).refined()
    }

    private fun selection(): SymbolDiscoverySelection {
        val lease = SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(7L).refined(),
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
                SymbolDiscoveryPattern.parse("call").refined(),
                SymbolDiscoveryMatch.FUZZY,
            ),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(10L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "call",
            lease,
            Path.of("/workspace/src/Service.kt"),
            "file:///workspace/src/Service.kt",
            7,
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
        return SymbolDiscoverySelection.select(batch, 0).refined()
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error("expected rejection")
        is Refinement.Rejected -> failure
    }
}
