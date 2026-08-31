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
    fun `incomplete empty evidence is qualified with continuation and never absence`() {
        val request = request(RelationMeaning.References)
        val collector = IntellijRelationCollector(request, clockNanoseconds = { 1L })

        val result = collector.finish(
            IntellijRelationTermination.Incomplete(setOf(RelationLimitation.PROVIDER_INCOMPLETE)),
        )

        val qualified = assertInstanceOf(RelationCompilation.Qualified::class.java, result)
        assertEquals(emptyList<RelationFact>(), qualified.batch.facts)
        assertEquals(0, qualified.coverage.knownMinimum.value)
        assertEquals(request.subject.fingerprint, qualified.coverage.continuation.subject)
        assertEquals(request.meaning, qualified.coverage.continuation.meaning)
        assertEquals(request.subject.lease.generation, qualified.coverage.continuation.generation)
    }

    private fun fact(request: RelationRequest): RelationFact {
        val subject = request.subject
        val related = RelationEndpoint.resolve(
            request.subject.lease,
            request.subject.scope,
            evidence(request.subject, "sample.Related.run()"),
        ).refined()
        val occurrence = RelationOccurrence.fromBoundary(
            request.subject.file,
            71,
            74,
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

    private fun request(meaning: RelationMeaning): RelationRequest = RelationRequest.start(
        selector = selector(),
        meaning = meaning,
        budget = RelationBudget(
            ResourceBudget(
                ResultLimit.parse(8).refined(),
                WorkUnitLimit.parse(32L).refined(),
                ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            RelationByteLimit.parse(100_000L).refined(),
        ),
    )

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
