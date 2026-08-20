package io.github.amichne.kast.symbol.contract

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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ExactDeclarationSelectorContractTest {
    @Test
    fun `selection can only retain a declaration stored by the exact batch`() {
        val candidate = candidate(offset = 7)
        val batch = batch(listOf(candidate))
        val selection = SymbolDiscoverySelection.select(batch, 0).refined()

        assertSame(candidate, selection.candidate)
        assertEquals(batch.lease, selection.lease)
        assertEquals(batch.scope, selection.scope)
        assertEquals(
            SymbolDiscoverySelectionFailure.NEGATIVE_ORDINAL,
            SymbolDiscoverySelection.select(batch, -1).rejected(),
        )
        assertEquals(
            SymbolDiscoverySelectionFailure.ORDINAL_OUT_OF_RANGE,
            SymbolDiscoverySelection.select(batch, 1).rejected(),
        )

        val fileBatch = batch(
            listOf(
                SymbolDiscoveryCandidate.fromBoundary(
                    kind = SymbolDiscoveryKind.FILE,
                    rawName = "Service.kt",
                    lease = lease(),
                    nativePath = Path.of("/workspace/src/Service.kt"),
                    virtualFileUrl = "file:///workspace/src/Service.kt",
                    rawOffset = null,
                ).refined(),
            ),
            kind = SymbolDiscoveryKind.FILE,
        )
        assertEquals(
            SymbolDiscoverySelectionFailure.FILE_IS_NOT_A_DECLARATION,
            SymbolDiscoverySelection.select(fileBatch, 0).rejected(),
        )
    }

    @Test
    fun `same name and file collisions issue distinct exact selectors by native evidence`() {
        val first = candidate(offset = 7)
        val second = candidate(offset = 41)
        val batch = batch(listOf(first, second))

        val firstSelector = ExactDeclarationSelector.issue(
            SymbolDiscoverySelection.select(batch, 0).refined(),
            evidence(start = 7, end = 17, qualifiedName = "sample.First.service"),
        ).refined()
        val secondSelector = ExactDeclarationSelector.issue(
            SymbolDiscoverySelection.select(batch, 1).refined(),
            evidence(start = 41, end = 55, qualifiedName = "sample.Second.service"),
        ).refined()

        assertNotEquals(firstSelector.fingerprint, secondSelector.fingerprint)
        assertEquals(7, firstSelector.range.startInclusive)
        assertEquals(41, secondSelector.range.startInclusive)
        assertEquals(
            "sample.First.service",
            (
                firstSelector.qualifiedIdentity
                    as ExactDeclarationQualifiedIdentity.Available
            ).value,
        )
        assertEquals("sample.FakeDeclaration", firstSelector.runtimeType.value)
    }

    @Test
    fun `selector fingerprints retain the complete discovery scope policy`() {
        val candidate = candidate(offset = 7)
        val sourceOnly = batch(
            candidates = listOf(candidate),
            scope = workspaceScope(SymbolLibraryPolicy.EXCLUDE),
        )
        val libraryReadable = batch(
            candidates = listOf(candidate),
            scope = workspaceScope(SymbolLibraryPolicy.INCLUDE),
        )

        val sourceSelector = ExactDeclarationSelector.issue(
            SymbolDiscoverySelection.select(sourceOnly, 0).refined(),
            evidence(start = 7, end = 17),
        ).refined()
        val librarySelector = ExactDeclarationSelector.issue(
            SymbolDiscoverySelection.select(libraryReadable, 0).refined(),
            evidence(start = 7, end = 17),
        ).refined()

        assertNotEquals(sourceSelector.fingerprint, librarySelector.fingerprint)
    }

    @Test
    fun `selector issuance and revalidation fail closed when evidence does not match`() {
        val selection = SymbolDiscoverySelection.select(batch(listOf(candidate(offset = 7))), 0)
            .refined()
        assertEquals(
            ExactDeclarationSelectorIssueFailure.START_OFFSET_MISMATCH,
            ExactDeclarationSelector.issue(
                selection,
                evidence(start = 8, end = 18),
            ).rejected(),
        )
        assertEquals(
            ExactDeclarationSelectorIssueFailure.NAME_MISMATCH,
            ExactDeclarationSelector.issue(
                selection,
                evidence(start = 7, end = 17, name = "other"),
            ).rejected(),
        )
        assertEquals(
            ExactDeclarationEvidenceFailure.INVALID_QUALIFIED_IDENTITY,
            ExactDeclarationQualifiedIdentity.fromBoundary(" ").rejected(),
        )

        val selector = ExactDeclarationSelector.issue(
            selection,
            evidence(start = 7, end = 17),
        ).refined()
        val current = RevalidatedExactDeclaration.validate(
            selector,
            evidence(start = 7, end = 17),
        ).refined()
        assertSame(selector, current.selector)
        assertEquals(
            ExactDeclarationRevalidationFailure.DECLARATION_MOVED_OR_CHANGED,
            RevalidatedExactDeclaration.validate(
                selector,
                evidence(start = 7, end = 18),
            ).rejected(),
        )
    }

    private fun candidate(
        offset: Int,
    ): SymbolDiscoveryCandidate = SymbolDiscoveryCandidate.fromBoundary(
        kind = SymbolDiscoveryKind.SYMBOL,
        rawName = "service",
        lease = lease(),
        nativePath = Path.of("/workspace/src/Service.kt"),
        virtualFileUrl = "file:///workspace/src/Service.kt",
        rawOffset = offset,
    ).refined()

    private fun evidence(
        start: Int,
        end: Int,
        name: String = "service",
        qualifiedName: String? = "sample.Service.service",
    ): ExactDeclarationEvidence = ExactDeclarationEvidence.fromBoundary(
        file = SymbolDiscoveryFileIdentity.fromBoundary(
            workspaceRoot = root(),
            nativePath = Path.of("/workspace/src/Service.kt"),
            virtualFileUrl = "file:///workspace/src/Service.kt",
        ).refined(),
        rawStartInclusive = start,
        rawEndExclusive = end,
        rawName = name,
        rawQualifiedIdentity = qualifiedName,
        rawRuntimeType = "sample.FakeDeclaration",
    ).refined()

    private fun batch(
        candidates: List<SymbolDiscoveryCandidate>,
        kind: SymbolDiscoveryKind = SymbolDiscoveryKind.SYMBOL,
        scope: SymbolSearchScope = workspaceScope(SymbolLibraryPolicy.EXCLUDE),
    ): SymbolDiscoveryBatch {
        val request = request(kind, candidates.size, scope)
        return SymbolDiscoveryBatch.create(
            request = request,
            candidates = candidates,
            encodedBytes = SymbolDiscoveryByteCount.parse(
                candidates.sumOf { it.projectedUtf8Size().value },
            ).refined(),
            examinedWorkUnits = SymbolDiscoveryWorkCount.parse(candidates.size.toLong()).refined(),
            timings = SymbolDiscoveryTimings(
                nativeQuery = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                projection = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
    }

    private fun request(
        kind: SymbolDiscoveryKind,
        resultLimit: Int,
        scope: SymbolSearchScope,
    ): SymbolDiscoveryRequest = SymbolDiscoveryRequest(
        scope = SymbolSearchScopeRequest(
            lease = lease(),
            scope = scope,
        ),
        kind = when (kind) {
            SymbolDiscoveryKind.FILE -> SymbolNameDiscoveryKind.FILE
            SymbolDiscoveryKind.CLASS -> SymbolNameDiscoveryKind.CLASS
            SymbolDiscoveryKind.SYMBOL -> SymbolNameDiscoveryKind.SYMBOL
            SymbolDiscoveryKind.TEXT -> error("text candidates are not declaration selectors")
        },
        pattern = SymbolDiscoveryPattern.parse("service").refined(),
        budget = SymbolDiscoveryBudget(
            resources = ResourceBudget(
                resultLimit = ResultLimit.parse(resultLimit.coerceAtLeast(1)).refined(),
                workUnitLimit = WorkUnitLimit.parse(100L).refined(),
                elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            returnedBytes = SymbolDiscoveryByteLimit.parse(10_000L).refined(),
        ),
    )

    private fun workspaceScope(
        libraries: SymbolLibraryPolicy,
    ): SymbolSearchScope.Workspace = SymbolSearchScope.Workspace(
        sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
        libraries = libraries,
    )

    private fun root(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun lease(): SemanticReadLease =
        SemanticReadLease(root(), EvidenceGeneration.parse(19L).refined())

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error(value.toString())
        is Refinement.Rejected -> failure
    }
}
