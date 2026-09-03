package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RelationReadTest {
    @Test
    fun `only classlike callers select constructor ownership confirmation`() {
        assertEquals(
            IntellijRelationPlanKind.ClassConstructionCallers,
            IntellijRelationPlanKind.derive(
                RelationMeaning.Callers,
                CompilerSymbolKind.CLASSLIKE,
            ),
        )
        assertEquals(
            IntellijRelationPlanKind.ExactReferences(IntellijExactReferenceShape.CALL),
            IntellijRelationPlanKind.derive(
                RelationMeaning.Callers,
                CompilerSymbolKind.FUNCTION,
            ),
        )
        assertEquals(
            IntellijRelationPlanKind.ExactReferences(IntellijExactReferenceShape.ANY),
            IntellijRelationPlanKind.derive(
                RelationMeaning.References,
                CompilerSymbolKind.CLASSLIKE,
            ),
        )
        assertEquals(
            IntellijRelationPlanKind.ExactReferences(IntellijExactReferenceShape.TYPE),
            IntellijRelationPlanKind.derive(
                RelationMeaning.TypeUses,
                CompilerSymbolKind.CLASSLIKE,
            ),
        )
    }

    @Test
    fun `resolved endpoint enters the next exact relation request without reconstruction`() {
        val initial = request(RelationMeaning.Callees)
        val related = assertInstanceOf(
            RelationEndpoint.Resolved::class.java,
            fact(initial).target,
        )

        val next = RelationRequest.start(
            related,
            RelationMeaning.References,
            initial.budget,
        )

        assertSame(related, next.subject)
        assertEquals(initial.subject.lease, next.subject.lease)
        assertEquals(initial.subject.scope, next.subject.scope)
        assertEquals(related.fingerprint, next.subject.fingerprint)
    }

    @Test
    fun `all seven closed meanings retain exact oriented facts`() {
        assertEquals(7, RelationMeaning.all.size)
        RelationMeaning.all.forEach { meaning ->
            val request = request(meaning)
            val fact = fact(request)
            val collector = IntellijRelationCollector(request, clockNanoseconds = { 1L })

            collector.beginProviderItem(providerItem("fact"))
            collector.accept(fact)
            val result = collector.finish(IntellijRelationTermination.Terminal)

            val complete = assertInstanceOf(RelationCompilation.Complete::class.java, result)
            assertEquals(listOf(fact), complete.batch.facts)
            assertEquals(request.subject.lease.generation, fact.generation)
            assertEquals(RelationProvenance.K2_AUTHORED_SOURCE, fact.provenance)
            if (meaning == RelationMeaning.Callees) {
                assertInstanceOf(RelationEndpoint.Subject::class.java, fact.source)
            } else {
                assertInstanceOf(RelationEndpoint.Subject::class.java, fact.target)
            }
        }
    }

    @Test
    fun `limit driven pages equal one terminal read for every relation meaning`() {
        RelationMeaning.all.forEach { meaning ->
            val start = request(meaning, resultLimit = 1)
            val selector = (start.subject as RelationEndpoint.Subject).selector
            val paged = mutableListOf<String>()
            var current = start
            var terminal = false
            var pageCount = 0

            while (!terminal && pageCount < 8) {
                pageCount += 1
                when (val page = compileStableProviderPage(current)) {
                    is RelationCompilation.Complete -> {
                        paged += page.batch.facts.map(RelationFact::canonicalProjection)
                        terminal = true
                    }
                    is RelationCompilation.Qualified -> {
                        paged += page.batch.facts.map(RelationFact::canonicalProjection)
                        val coverage = assertInstanceOf(
                            io.github.amichne.kast.relation.contract
                                .RelationIncompleteCoverage.Resumable::class.java,
                            page.coverage,
                        )
                        current = RelationRequest.resume(
                            selector,
                            meaning,
                            start.budget,
                            coverage.continuation,
                        ).refined()
                    }
                    is RelationCompilation.Rejected -> error(page.reason)
                }
            }

            assertTrue(terminal, "pagination did not terminate for $meaning")
            assertEquals(paged.size, paged.distinct().size, "duplicate page facts for $meaning")

            val highLimit = request(meaning, resultLimit = 8)
            val expected = assertInstanceOf(
                RelationCompilation.Complete::class.java,
                compileStableProviderPage(highLimit),
            ).batch.facts.map(RelationFact::canonicalProjection)
            assertEquals(expected.sorted(), paged.sorted(), "omitted page facts for $meaning")
        }
    }

    @Test
    fun `provider exhausted incomplete evidence is terminal and has no continuation`() {
        val request = request(RelationMeaning.References)
        val collector = IntellijRelationCollector(request, clockNanoseconds = { 1L })
        collector.beginProviderItem(providerItem("unresolved"))
        collector.examineIncomplete(RelationLimitation.UNRESOLVED_TARGET)

        val result = collector.finish(
            IntellijRelationTermination.TerminalIncomplete(
                setOf(RelationLimitation.UNRESOLVED_TARGET),
            ),
        )

        val qualified = assertInstanceOf(RelationCompilation.Qualified::class.java, result)
        assertEquals(emptyList<RelationFact>(), qualified.batch.facts)
        assertEquals(0, qualified.coverage.knownMinimum.value)
        assertEquals(1L, qualified.batch.examinedWorkUnits.value)
        assertEquals(0, qualified.batch.resultCount.value)
        assertInstanceOf(
            io.github.amichne.kast.relation.contract.RelationIncompleteCoverage.TerminalIncomplete::class.java,
            qualified.coverage,
        )
    }

    @Test
    fun `filtered provider item advances cursor without manufacturing work or result`() {
        val request = request(RelationMeaning.References)
        val collector = IntellijRelationCollector(request, clockNanoseconds = { 1L })

        collector.beginProviderItem(providerItem("filtered"))
        collector.dismissProviderItem()
        val result = collector.finish(
            IntellijRelationTermination.Resumable(setOf(RelationLimitation.PROVIDER_INCOMPLETE)),
        )

        val qualified = assertInstanceOf(RelationCompilation.Qualified::class.java, result)
        val coverage = assertInstanceOf(
            io.github.amichne.kast.relation.contract.RelationIncompleteCoverage.Resumable::class.java,
            qualified.coverage,
        )
        assertEquals(1L, coverage.continuation.nextProviderCursor.nextPosition.value)
        assertEquals(0L, qualified.batch.examinedWorkUnits.value)
        assertEquals(0, qualified.batch.resultCount.value)
    }

    @Test
    fun `limit before the first provider item rejects instead of issuing a looping cursor`() {
        val request = request(RelationMeaning.References)
        var clockRead = 0
        val collector = IntellijRelationCollector(
            request,
            clockNanoseconds = {
                if (clockRead++ == 0) 0L else 1_000_000_000L
            },
        )

        assertEquals(
            IntellijRelationProviderItemAdmission.HALTED,
            collector.beginProviderItem(providerItem("first")),
        )
        assertEquals(
            io.github.amichne.kast.relation.contract.RelationCompilerRejection
                .COMPILER_CONTRACT_VIOLATION,
            assertInstanceOf(
                RelationCompilation.Rejected::class.java,
                collector.finish(IntellijRelationTermination.Resumable(emptySet())),
            ).reason,
        )
    }

    @Test
    fun `moved provider prefix rejects instead of resuming by numeric position`() {
        val request = request(RelationMeaning.References)
        val batch = emptyBatch(request)
        val expected = request.providerCursor.advance(providerItem("expected"))
        val resumable = RelationCompilation.qualifiedResumable(
            batch,
            setOf(RelationLimitation.RESULT_LIMIT_REACHED),
            expected,
        ).refined()
        val continuation = (
            resumable.coverage as
                io.github.amichne.kast.relation.contract.RelationIncompleteCoverage.Resumable
            ).continuation
        val resumed = RelationRequest.resume(
            (request.subject as RelationEndpoint.Subject).selector,
            request.meaning,
            request.budget,
            continuation,
        ).refined()
        val collector = IntellijRelationCollector(resumed, clockNanoseconds = { 1L })

        assertEquals(
            IntellijRelationProviderItemAdmission.CURSOR_MOVED,
            collector.beginProviderItem(providerItem("changed")),
        )
        assertEquals(
            io.github.amichne.kast.relation.contract.RelationCompilerRejection.CONTINUATION_CURSOR_MOVED,
            assertInstanceOf(
                RelationCompilation.Rejected::class.java,
                collector.finish(IntellijRelationTermination.Resumable(emptySet())),
            ).reason,
        )
    }

    @Test
    fun `result limit leaves the first unreturned provider item for resume`() {
        val request = request(RelationMeaning.References, resultLimit = 1)
        val collector = IntellijRelationCollector(request, clockNanoseconds = { 1L })

        collector.beginProviderItem(providerItem("first"))
        assertTrue(collector.accept(fact(request)))
        collector.beginProviderItem(providerItem("second"))
        org.junit.jupiter.api.Assertions.assertFalse(collector.accept(fact(request)))
        val qualified = assertInstanceOf(
            RelationCompilation.Qualified::class.java,
            collector.finish(IntellijRelationTermination.Resumable(emptySet())),
        )
        val coverage = assertInstanceOf(
            io.github.amichne.kast.relation.contract.RelationIncompleteCoverage.Resumable::class.java,
            qualified.coverage,
        )

        assertEquals(1L, coverage.continuation.nextProviderCursor.nextPosition.value)
        assertEquals(1L, qualified.batch.examinedWorkUnits.value)
        assertEquals(1, qualified.batch.resultCount.value)
    }

    private fun providerItem(value: String) =
        io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor.parse(value).refined()

    private fun compileStableProviderPage(request: RelationRequest): RelationCompilation {
        val collector = IntellijRelationCollector(request, clockNanoseconds = { 1L })
        val items = listOf(
            ProviderFixture("filtered-before", null),
            ProviderFixture("first", FactFixture("sample.Related.first()", 71)),
            ProviderFixture("filtered-between", null),
            ProviderFixture("second", FactFixture("sample.Related.second()", 91)),
        )
        for (item in items) {
            when (collector.beginProviderItem(providerItem(item.descriptor))) {
                IntellijRelationProviderItemAdmission.SKIPPED_VERIFIED_PREFIX -> continue
                IntellijRelationProviderItemAdmission.READY -> when (val fixture = item.fact) {
                    null -> assertTrue(collector.dismissProviderItem())
                    else -> if (!collector.accept(fact(request, fixture.identity, fixture.offset))) {
                        return collector.finish(IntellijRelationTermination.Resumable(emptySet()))
                    }
                }
                IntellijRelationProviderItemAdmission.HALTED,
                IntellijRelationProviderItemAdmission.CURSOR_MOVED,
                    -> return collector.finish(IntellijRelationTermination.Resumable(emptySet()))
            }
        }
        return collector.finish(IntellijRelationTermination.Terminal)
    }

    private fun fact(
        request: RelationRequest,
        identity: String = "sample.Related.run()",
        occurrenceOffset: Int = 71,
    ): RelationFact {
        val subject = request.subject
        val related = RelationEndpoint.resolve(
            request.subject.lease,
            request.subject.scope,
            evidence(request.subject, identity),
        ).refined()
        val occurrence = RelationOccurrence.fromBoundary(
            request.subject.file,
            occurrenceOffset,
            occurrenceOffset + 3,
        ).refined()
        val (source, target) = if (request.meaning == RelationMeaning.Callees) {
            subject to related
        } else {
            related to subject
        }
        return RelationFact.create(
            request,
            source,
            target,
            occurrence,
            RelationProvenance.K2_AUTHORED_SOURCE,
        ).refined()
    }

    private fun request(
        meaning: RelationMeaning,
        resultLimit: Int = 8,
    ): RelationRequest = RelationRequest.start(
        selector = selector(),
        meaning = meaning,
        budget = RelationBudget(
            ResourceBudget(
                ResultLimit.parse(resultLimit).refined(),
                WorkUnitLimit.parse(32L).refined(),
                ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            RelationByteLimit.parse(100_000L).refined(),
        ),
    )

    private fun emptyBatch(request: RelationRequest): io.github.amichne.kast.relation.contract.RelationBatch =
        io.github.amichne.kast.relation.contract.RelationBatch.create(
            request,
            emptyList(),
            io.github.amichne.kast.relation.contract.RelationByteCount.parse(0L).refined(),
            io.github.amichne.kast.relation.contract.RelationWorkCount.parse(0L).refined(),
            io.github.amichne.kast.relation.contract.RelationResultCount.parse(0).refined(),
        ).refined()

    private fun selector(): SymbolSelector {
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(
                lease(),
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
            request.scope.lease,
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
        return SymbolSelector.issue(selection, evidenceForSelection(selection)).refined()
    }

    private fun evidenceForSelection(
        selection: SymbolDiscoverySelection,
    ): CompilerGroundedSymbolEvidence {
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        return CompilerGroundedSymbolEvidence.fromBoundary(
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
    }

    private fun evidence(
        subject: RelationEndpoint,
        identity: String,
    ): CompilerGroundedSymbolEvidence = CompilerGroundedSymbolEvidence.fromBoundary(
        subject.file,
        71,
        82,
        "run",
        "sample.Related.run",
        CompilerSymbolKind.FUNCTION,
        CanonicalCompilerSignature.function(
            "sample.Related.run",
            null,
            emptyList(),
            listOf(identity),
            0,
        ).refined(),
    ).refined()

    private fun lease(): SemanticReadLease = SemanticReadLease(
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
        EvidenceGeneration.parse(19L).refined(),
    )

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private data class ProviderFixture(
        val descriptor: String,
        val fact: FactFixture?,
    )

    private data class FactFixture(
        val identity: String,
        val offset: Int,
    )
}

internal class ClassConstructionCallerFixture(val marker: Int) {
    constructor() : this(0)

    fun member(): Int = marker

    companion object {
        operator fun invoke(marker: String): ClassConstructionCallerFixture =
            ClassConstructionCallerFixture(marker.length)
    }
}

internal class ClassConstructionCallerFixtureUses {
    fun primaryConstructor(): ClassConstructionCallerFixture =
        ClassConstructionCallerFixture(1)

    fun secondaryConstructor(): ClassConstructionCallerFixture =
        ClassConstructionCallerFixture()

    fun typeOnly(value: ClassConstructionCallerFixture): ClassConstructionCallerFixture = value

    fun member(value: ClassConstructionCallerFixture): Int = value.member()

    fun callableReference(): (Int) -> ClassConstructionCallerFixture =
        ::ClassConstructionCallerFixture

    fun invokeFunction(): ClassConstructionCallerFixture =
        ClassConstructionCallerFixture("not-a-constructor")
}

internal object ClassConstructionCallerCollisionScope {
    class ClassConstructionCallerFixture

    fun unrelatedSameName(): ClassConstructionCallerFixture = ClassConstructionCallerFixture()
}
