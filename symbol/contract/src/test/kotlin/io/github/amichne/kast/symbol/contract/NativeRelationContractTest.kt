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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.nio.file.Path

class NativeRelationContractTest {
    @Test
    fun `relation facts retain the exact subject lease scope endpoint and occurrence`() {
        val selector = selector()
        val evidence = evidence("sample.Client.call", 40, 50)
        val endpoint = ExactRelationEndpoint.bind(selector, evidence)
        val occurrence = NativeRelationOccurrence.fromBoundary(
            file = fileIdentity("/workspace/src/Client.kt"),
            rawStartInclusive = 44,
            rawEndExclusive = 48,
        ).refined()
        val fact = NativeRelationFact.create(
            subject = selector,
            family = NativeRelationFamily.CALLERS,
            related = endpoint,
            occurrence = occurrence,
        ).refined()

        assertSame(selector, fact.subject)
        assertEquals(selector.lease, fact.related.lease)
        assertEquals(selector.scope, fact.related.scope)
        assertEquals(evidence, fact.related.evidence)
        assertEquals(44, fact.occurrence.range.startInclusive)
        assertEquals(
            ExactDeclarationEvidenceFailure.INVALID_RANGE,
            NativeRelationOccurrence.fromBoundary(
                fileIdentity("/workspace/src/Client.kt"),
                4,
                4,
            ).rejected(),
        )
    }

    @Test
    fun `facts reject endpoints bound under another exact scope`() {
        val subject = selector(libraries = SymbolLibraryPolicy.EXCLUDE)
        val otherScope = selector(libraries = SymbolLibraryPolicy.INCLUDE)
        val related = ExactRelationEndpoint.bind(
            otherScope,
            evidence("sample.Client.call", 40, 50),
        )

        assertEquals(
            NativeRelationFactFailure.ENDPOINT_SCOPE_MISMATCH,
            NativeRelationFact.create(
                subject = subject,
                family = NativeRelationFamily.REFERENCES,
                related = related,
                occurrence = NativeRelationOccurrence.fromBoundary(
                    fileIdentity("/workspace/src/Client.kt"),
                    44,
                    48,
                ).refined(),
            ).rejected(),
        )
    }

    @Test
    fun `terminal and qualified coverage cannot exchange exact and minimum counts`() {
        val request = relationRequest()
        val fact = fact(request.selector, NativeRelationFamily.REFERENCES, 40)
        val batch = NativeRelationBatch.create(
            request = request,
            facts = listOf(fact),
            encodedBytes = fact.projectedUtf8Size(),
            examinedWorkUnits = NativeRelationWorkCount.parse(1L).refined(),
            timings = timings(),
        ).refined()

        val complete = NativeRelationOutcome.complete(batch)
        assertEquals(1, complete.exactCount.value)

        val qualified = NativeRelationOutcome.qualified(
            batch,
            setOf(NativeRelationLimitation.PROVIDER_INCOMPLETE),
        ).refined()
        assertEquals(1, qualified.knownMinimumCount.value)
        assertEquals(
            setOf(NativeRelationLimitation.PROVIDER_INCOMPLETE),
            qualified.limitations.values,
        )
        assertEquals(
            NativeRelationLimitationsFailure.EMPTY,
            NativeRelationOutcome.qualified(batch, emptySet()).rejected(),
        )
    }

    @Test
    fun `batch rejects wrong subject family order and byte claims`() {
        val request = relationRequest()
        val first = fact(request.selector, NativeRelationFamily.REFERENCES, 40)
        val second = fact(request.selector, NativeRelationFamily.REFERENCES, 60)
        val ordered = listOf(first, second).sorted()
        val bytes = NativeRelationByteCount.parse(
            ordered.sumOf { it.projectedUtf8Size().value },
        ).refined()

        assertEquals(
            NativeRelationBatchFailure.NON_DETERMINISTIC_ORDER,
            NativeRelationBatch.create(
                request,
                ordered.reversed(),
                bytes,
                NativeRelationWorkCount.parse(2L).refined(),
                timings(),
            ).rejected(),
        )
        assertEquals(
            NativeRelationBatchFailure.ENCODED_BYTE_COUNT_MISMATCH,
            NativeRelationBatch.create(
                request,
                ordered,
                NativeRelationByteCount.parse(0L).refined(),
                NativeRelationWorkCount.parse(2L).refined(),
                timings(),
            ).rejected(),
        )
        val wrongFamily = fact(request.selector, NativeRelationFamily.CALLERS, 80)
        assertEquals(
            NativeRelationBatchFailure.FAMILY_MISMATCH,
            NativeRelationBatch.create(
                request,
                listOf(wrongFamily),
                wrongFamily.projectedUtf8Size(),
                NativeRelationWorkCount.parse(1L).refined(),
                timings(),
            ).rejected(),
        )
        val wrongSubject = fact(selector(), NativeRelationFamily.REFERENCES, 100)
        assertEquals(
            NativeRelationBatchFailure.SUBJECT_MISMATCH,
            NativeRelationBatch.create(
                request,
                listOf(wrongSubject),
                wrongSubject.projectedUtf8Size(),
                NativeRelationWorkCount.parse(1L).refined(),
                timings(),
            ).rejected(),
        )
        assertEquals(
            NativeRelationByteLimitFailure.NOT_POSITIVE,
            NativeRelationByteLimit.parse(0L).rejected(),
        )
    }

    private fun fact(
        selector: ExactDeclarationSelector,
        family: NativeRelationFamily,
        offset: Int,
    ): NativeRelationFact {
        val evidence = evidence("sample.Client.call$offset", offset, offset + 8)
        return NativeRelationFact.create(
            subject = selector,
            family = family,
            related = ExactRelationEndpoint.bind(selector, evidence),
            occurrence = NativeRelationOccurrence.fromBoundary(
                evidence.file,
                offset + 2,
                offset + 6,
            ).refined(),
        ).refined()
    }

    private fun relationRequest(): NativeRelationRequest = NativeRelationRequest(
        selector = selector(),
        family = NativeRelationFamily.REFERENCES,
        budget = NativeRelationBudget(
            resources = ResourceBudget(
                resultLimit = ResultLimit.parse(2).refined(),
                workUnitLimit = WorkUnitLimit.parse(100L).refined(),
                elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            returnedBytes = NativeRelationByteLimit.parse(10_000L).refined(),
        ),
    )

    private fun selector(
        libraries: SymbolLibraryPolicy = SymbolLibraryPolicy.EXCLUDE,
    ): ExactDeclarationSelector {
        val scope = SymbolSearchScope.Workspace(
            sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
            libraries = libraries,
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            kind = SymbolDiscoveryKind.SYMBOL,
            rawName = "service",
            lease = lease(),
            nativePath = Path.of("/workspace/src/Service.kt"),
            virtualFileUrl = "file:///workspace/src/Service.kt",
            rawOffset = 7,
        ).refined()
        val discoveryRequest = SymbolDiscoveryRequest(
            scope = SymbolSearchScopeRequest(lease(), scope),
            kind = SymbolNameDiscoveryKind.SYMBOL,
            pattern = SymbolDiscoveryPattern.parse("service").refined(),
            budget = SymbolDiscoveryBudget(
                resources = ResourceBudget(
                    resultLimit = ResultLimit.parse(1).refined(),
                    workUnitLimit = WorkUnitLimit.parse(10L).refined(),
                    elapsedTimeLimit = ElapsedTimeLimitMillis.parse(100L).refined(),
                ),
                returnedBytes = SymbolDiscoveryByteLimit.parse(1_000L).refined(),
            ),
        )
        val batch = SymbolDiscoveryBatch.create(
            request = discoveryRequest,
            candidates = listOf(candidate),
            encodedBytes = candidate.projectedUtf8Size(),
            examinedWorkUnits = SymbolDiscoveryWorkCount.parse(1L).refined(),
            timings = SymbolDiscoveryTimings(
                nativeQuery = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                projection = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
        return ExactDeclarationSelector.issue(
            SymbolDiscoverySelection.select(batch, 0).refined(),
            evidence("sample.Service.service", 7, 17, "/workspace/src/Service.kt", "service"),
        ).refined()
    }

    private fun evidence(
        qualifiedName: String,
        start: Int,
        end: Int,
        path: String = "/workspace/src/Client.kt",
        name: String = "call$start",
    ): ExactDeclarationEvidence = ExactDeclarationEvidence.fromBoundary(
        file = fileIdentity(path),
        rawStartInclusive = start,
        rawEndExclusive = end,
        rawName = name,
        rawQualifiedIdentity = qualifiedName,
        rawRuntimeType = "sample.FakeDeclaration",
    ).refined()

    private fun fileIdentity(path: String): SymbolDiscoveryFileIdentity =
        SymbolDiscoveryFileIdentity.fromBoundary(
            workspaceRoot = root(),
            nativePath = Path.of(path),
            virtualFileUrl = "file://$path",
        ).refined()

    private fun timings(): NativeRelationTimings = NativeRelationTimings(
        nativeQuery = NativeRelationElapsedNanoseconds.parse(1L).refined(),
        projection = NativeRelationElapsedNanoseconds.parse(1L).refined(),
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
