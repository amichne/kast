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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SymbolDiscoveryContractTest {
    @Test
    fun `pattern and byte bounds refine raw request primitives`() {
        assertEquals(
            SymbolDiscoveryPatternFailure.BLANK,
            SymbolDiscoveryPattern.parse(" ").rejected(),
        )
        assertEquals(
            SymbolDiscoveryPatternFailure.CONTROL_CHARACTER,
            SymbolDiscoveryPattern.parse("bad\npattern").rejected(),
        )
        assertEquals(
            SymbolDiscoveryPatternFailure.TOO_LONG,
            SymbolDiscoveryPattern.parse("a".repeat(257)).rejected(),
        )
        assertEquals(
            SymbolDiscoveryByteLimitFailure.NOT_POSITIVE,
            SymbolDiscoveryByteLimit.parse(0L).rejected(),
        )
        assertEquals("Service", SymbolDiscoveryPattern.parse("Service").refined().value)
        assertEquals(4096L, SymbolDiscoveryByteLimit.parse(4096L).refined().value)
    }

    @Test
    fun `candidate transition distinguishes workspace files external files and declarations`() {
        val workspaceFile = candidate(
            kind = SymbolDiscoveryKind.FILE,
            name = "App.kt",
            path = Path.of("/workspace/src/App.kt"),
            url = "file:///workspace/src/App.kt",
            offset = null,
        )
        val libraryClass = candidate(
            kind = SymbolDiscoveryKind.CLASS,
            name = "LibraryType",
            path = Path.of("/libraries/library.jar"),
            url = "jar:///libraries/library.jar!/LibraryType.class",
            offset = 17,
        )

        assertInstanceOf(
            SymbolDiscoveryFileIdentity.Workspace::class.java,
            workspaceFile.location.file,
        )
        assertInstanceOf(
            SymbolDiscoveryFileIdentity.External::class.java,
            libraryClass.location.file,
        )
        assertInstanceOf(
            SymbolDiscoveryCandidateLocation.Declaration::class.java,
            libraryClass.location,
        )
        assertEquals(
            SymbolDiscoveryCandidateFailure.FILE_CANDIDATE_HAS_DECLARATION_OFFSET,
            SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.FILE,
                "App.kt",
                lease(),
                Path.of("/workspace/src/App.kt"),
                "file:///workspace/src/App.kt",
                1,
            ).rejected(),
        )
        assertEquals(
            SymbolDiscoveryCandidateFailure.DECLARATION_CANDIDATE_MISSING_OFFSET,
            SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.SYMBOL,
                "run",
                lease(),
                Path.of("/workspace/src/App.kt"),
                "file:///workspace/src/App.kt",
                null,
            ).rejected(),
        )
        assertEquals(
            SymbolDiscoveryCandidateFailure.INVALID_FILE_LOCATION,
            SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.CLASS,
                "Broken",
                lease(),
                null,
                "no-scheme",
                0,
            ).rejected(),
        )
    }

    @Test
    fun `batch construction rejects overflow reordered candidates and false byte evidence`() {
        val first = candidate(
            SymbolDiscoveryKind.SYMBOL,
            "Alpha",
            Path.of("/workspace/src/Alpha.kt"),
            "file:///workspace/src/Alpha.kt",
            1,
        )
        val second = candidate(
            SymbolDiscoveryKind.SYMBOL,
            "Beta",
            Path.of("/workspace/src/Beta.kt"),
            "file:///workspace/src/Beta.kt",
            2,
        )
        val request = request(resultLimit = 1)
        val timings = SymbolDiscoveryTimings(
            nativeQuery = SymbolDiscoveryElapsedNanoseconds.parse(10L).refined(),
            projection = SymbolDiscoveryElapsedNanoseconds.parse(5L).refined(),
        )
        val work = SymbolDiscoveryWorkCount.parse(4L).refined()
        val otherGenerationCandidate = candidate(
            kind = SymbolDiscoveryKind.SYMBOL,
            name = "Other",
            path = Path.of("/workspace/src/Other.kt"),
            url = "file:///workspace/src/Other.kt",
            offset = 3,
            candidateLease = SemanticReadLease(
                workspaceRoot(),
                EvidenceGeneration.parse(4L).refined(),
            ),
        )

        assertEquals(
            SymbolDiscoveryBatchFailure.CANDIDATE_LEASE_MISMATCH,
            SymbolDiscoveryBatch.create(
                request,
                listOf(otherGenerationCandidate),
                otherGenerationCandidate.projectedUtf8Size(),
                work,
                timings,
            ).rejected(),
        )
        assertEquals(
            SymbolDiscoveryBatchFailure.RESULT_LIMIT_EXCEEDED,
            SymbolDiscoveryBatch.create(
                request,
                listOf(first, second),
                SymbolDiscoveryByteCount.parse(
                    first.projectedUtf8Size().value + second.projectedUtf8Size().value,
                ).refined(),
                work,
                timings,
            ).rejected(),
        )
        assertEquals(
            SymbolDiscoveryBatchFailure.NON_DETERMINISTIC_ORDER,
            SymbolDiscoveryBatch.create(
                request(resultLimit = 2),
                listOf(second, first),
                SymbolDiscoveryByteCount.parse(
                    first.projectedUtf8Size().value + second.projectedUtf8Size().value,
                ).refined(),
                work,
                timings,
            ).rejected(),
        )
        assertEquals(
            SymbolDiscoveryBatchFailure.ENCODED_BYTE_COUNT_MISMATCH,
            SymbolDiscoveryBatch.create(
                request,
                listOf(first),
                SymbolDiscoveryByteCount.parse(0L).refined(),
                work,
                timings,
            ).rejected(),
        )
        assertEquals(
            SymbolDiscoveryQualificationFailure.EMPTY,
            SymbolDiscoveryQualifications.from(emptySet()).rejected(),
        )
    }

    private fun candidate(
        kind: SymbolDiscoveryKind,
        name: String,
        path: Path?,
        url: String,
        offset: Int?,
        candidateLease: SemanticReadLease = lease(),
    ): SymbolDiscoveryCandidate =
        SymbolDiscoveryCandidate.fromBoundary(
            kind = kind,
            rawName = name,
            lease = candidateLease,
            nativePath = path,
            virtualFileUrl = url,
            rawOffset = offset,
        ).refined()

    private fun request(
        resultLimit: Int,
    ): SymbolDiscoveryRequest = SymbolDiscoveryRequest(
        scope = SymbolSearchScopeRequest(
            lease = SemanticReadLease(
                workspaceRoot = workspaceRoot(),
                generation = EvidenceGeneration.parse(3L).refined(),
            ),
            scope = SymbolSearchScope.Workspace(
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                libraries = SymbolLibraryPolicy.EXCLUDE,
            ),
        ),
        target = SymbolDiscoveryTarget.Name(
            kind = SymbolNameDiscoveryKind.SYMBOL,
            pattern = SymbolDiscoveryPattern.parse("Type").refined(),
            match = SymbolDiscoveryMatch.FUZZY,
        ),
        budget = SymbolDiscoveryBudget(
            resources = ResourceBudget(
                resultLimit = ResultLimit.parse(resultLimit).refined(),
                workUnitLimit = WorkUnitLimit.parse(100L).refined(),
                elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            returnedBytes = SymbolDiscoveryByteLimit.parse(10_000L).refined(),
        ),
    )

    private fun workspaceRoot(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun lease(): SemanticReadLease =
        SemanticReadLease(workspaceRoot(), EvidenceGeneration.parse(3L).refined())

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error(value.toString())
        is Refinement.Rejected -> failure
    }
}
