package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactSourceTest {
    @Test
    fun `producer requires exact confirmed invocation result`() {
        val f = Fixture()
        val local = ValueSite.fromCompiler(f.owner, f.invocation.range, ValueRole.LocalBinding).value()
        assertEquals(
            Refinement.Rejected(QueryImpactProducerFailure.NOT_INVOCATION_RESULT),
            QueryImpactProducer.admit(local, f.invocation),
        )
        val other =
            ValueInvocation.fromCompiler(f.owner, ExactDeclarationTextRange.parse(40, 50).value(), f.owner).value()
        assertEquals(
            Refinement.Rejected(QueryImpactProducerFailure.NOT_INVOCATION_RESULT),
            QueryImpactProducer.admit(other.resultSite(), f.invocation),
        )
        assertEquals(
            f.invocation,
            QueryImpactProducer.admit(f.invocation.resultSite(), f.invocation).value().invocation,
        )
    }

    @Test
    fun `empty duplicate and foreign authority seeds fail before query effects`() {
        val f = Fixture()
        fun admit(producers: List<QueryImpactProducer>) =
            QueryImpactSource.admit(producers, emptyList(), emptyList(), RelationSearchBoundary.RETAINED_SUBJECT)
        assertEquals(Refinement.Rejected(QueryImpactSourceFailure.EMPTY_PRODUCERS), admit(emptyList()))
        assertEquals(
            Refinement.Rejected(QueryImpactSourceFailure.DUPLICATE_PRODUCER),
            admit(listOf(f.producer, f.producer)),
        )
        assertEquals(
            Refinement.Rejected(QueryImpactSourceFailure.FOREIGN_BASIS),
            admit(listOf(f.producer, Fixture(2).producer)),
        )
    }

    @Test
    fun `admitted input copies producer list and preserves domain and semantics`() {
        val f = Fixture()
        val producers = mutableListOf(f.producer)
        val domain = RelationSearchBoundary.RETAINED_SUBJECT
        val source = QueryImpactSource.admit(producers, emptyList(), emptyList(), domain).value()
        producers.clear()
        assertEquals(listOf(f.producer), source.producers)
        assertSame(domain, source.domain)
        assertEquals(QueryImpactFlowSemantics.KOTLIN_FORWARD_V1, source.semantics)
        assertEquals(f.lease, source.lease)
        assertTrue(source.retainedBytes > f.producer.site.retainedBytes)
    }

    @Test
    fun `value source excludes incompatible legacy steps and output at plan admission`() {
        val f = Fixture()
        val source =
            QuerySourceSyntax.Impact(
                QueryImpactSource.admit(
                        listOf(f.producer),
                        emptyList(),
                        emptyList(),
                        RelationSearchBoundary.RETAINED_SUBJECT,
                    )
                    .value()
            )
        val invalid = QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.OutputTypeMismatch)
        assertEquals(
            invalid,
            QueryPlanCompiler.admit(
                QueryPlanSyntax(
                    source,
                    listOf(QueryStepSyntax.Related(RelationMeaning.Callees)),
                    QueryOutputSyntax.ValuePaths,
                )
            ),
        )
        assertEquals(
            invalid,
            QueryPlanCompiler.admit(QueryPlanSyntax(source, emptyList(), QueryOutputSyntax.Occurrences)),
        )
        val plan =
            (QueryPlanCompiler.admit(QueryPlanSyntax(source, emptyList(), QueryOutputSyntax.ValuePaths))
                    as QueryPlanAdmission.Admitted)
                .plan
        assertTrue(plan is AdmittedQueryPlan.Impact)
    }

    private class Fixture(generation: Long = 1) {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")).value(),
                EvidenceGeneration.parse(generation).value(),
            )
        private val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        private val file =
            (SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.SYMBOL,
                        "owner",
                        lease,
                        Path.of("/fixture/File.kt"),
                        "file:///fixture/File.kt",
                        0,
                    )
                    .value()
                    .location as SymbolDiscoveryCandidateLocation.Declaration)
                .file
        private val proof =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    0,
                    100,
                    "owner",
                    "fixture.owner",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("fixture.owner", null, emptyList(), emptyList(), 0).value(),
                )
                .value()
        val owner = RelationEndpoint.resolve(lease, scope, proof).value()
        val invocation =
            ValueInvocation.fromCompiler(owner, ExactDeclarationTextRange.parse(10, 20).value(), owner).value()
        val producer = QueryImpactProducer.admit(invocation.resultSite(), invocation).value()
    }
}

private fun <V> Refinement<V, *>.value(): V = (this as Refinement.Refined).value
