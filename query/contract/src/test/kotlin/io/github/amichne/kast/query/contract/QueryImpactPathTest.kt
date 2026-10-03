package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryRequiredEvidence
import io.github.amichne.kast.relation.contract.BoundaryUnresolvedReason
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
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
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class QueryImpactPathTest {
    @Test
    fun `a path cannot attach a disconnected compiler edge even within the same declaration`() {
        val fixture = Fixture()
        val producer = fixture.site(10, ValueRole.ExpressionResult)
        val unrelated = fixture.site(20, ValueRole.ExpressionResult)
        val destination = fixture.site(30, ValueRole.LocalBinding)
        val step =
            QueryImpactStep.Compiler(
                ValueTransfer.fromCompiler(unrelated, destination, ValueTransferKind.LOCAL_BINDING).refined()
            )
        assertEquals(
            Refinement.Rejected(QueryImpactPathFailure.DISCONNECTED_STEP),
            QueryImpactPath.fromEvidence(
                producer,
                listOf(step),
                QueryImpactRepresentation.NotModeled,
                fixture.terminal(destination),
            ),
        )
    }

    @Test
    fun `domain exhaustion cannot become terminal accounting while outgoing flow remains`() {
        val fixture = Fixture()
        val source = fixture.site(10, ValueRole.ExpressionResult)
        val target = fixture.site(20, ValueRole.LocalBinding)
        val transfer = ValueTransfer.fromCompiler(source, target, ValueTransferKind.LOCAL_BINDING).refined()
        val observation =
            ValueFlowStep.fromCompiler(
                    source,
                    listOf(transfer),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    fixture.domain,
                    RelationWorkCount.parse(1).refined(),
                )
                .refined()
        assertEquals(
            Refinement.Rejected(QueryImpactPathFailure.TERMINAL_UNPROVEN),
            QueryImpactTerminal.SupportedDomainEnd.admit(observation),
        )
    }

    @Test
    fun `two routes to the same destination remain distinct report paths`() {
        val fixture = Fixture()
        val producer = fixture.site(10, ValueRole.ExpressionResult)
        val binding = fixture.site(20, ValueRole.LocalBinding)
        val read = fixture.site(30, ValueRole.LocalRead)
        val destination = fixture.site(40, ValueRole.PropertyAssignment)
        val direct =
            QueryImpactStep.Compiler(
                ValueTransfer.fromCompiler(producer, destination, ValueTransferKind.PROPERTY_ASSIGNMENT).refined()
            )
        val indirect =
            listOf(
                QueryImpactStep.Compiler(
                    ValueTransfer.fromCompiler(producer, binding, ValueTransferKind.LOCAL_BINDING).refined()
                ),
                QueryImpactStep.Compiler(
                    ValueTransfer.fromCompiler(binding, read, ValueTransferKind.LOCAL_READ).refined()
                ),
                QueryImpactStep.Compiler(
                    ValueTransfer.fromCompiler(read, destination, ValueTransferKind.PROPERTY_ASSIGNMENT).refined()
                ),
            )
        val terminal = fixture.terminal(destination)
        val first =
            QueryImpactPath.fromEvidence(producer, listOf(direct), QueryImpactRepresentation.NotModeled, terminal)
                .refined()
        val second =
            QueryImpactPath.fromEvidence(producer, indirect, QueryImpactRepresentation.NotModeled, terminal).refined()
        assertNotEquals(first, second)
        assertEquals(2, setOf(first, second).size)
    }

    @Test
    fun `modeled cross repository path retains separate bases and unresolved downstream boundary`() {
        val server = Fixture("/server", 7)
        val client = Fixture("/client", 19)
        val producer = server.site(10, ValueRole.PropertyAssignment)
        val destination = client.site(20, ValueRole.PropertyAssignment)
        val version = ModelVersion.parse(1).refined()
        val contract = BoundaryContractIdentity(ModelIdentifier.parse("wire-v1").refined(), version)
        val source =
            BoundaryPosition.at(
                producer,
                BoundaryKind.SERIALIZATION,
                contract,
                ModelIdentifier.parse("payload").refined(),
            )
        val target =
            BoundaryPosition.at(
                destination,
                BoundaryKind.SERIALIZATION,
                contract,
                ModelIdentifier.parse("payload").refined(),
            )
        val modelIdentity =
            ContractModelIdentity(
                ModelIdentifier.parse("wire-model").refined(),
                version,
                ModelIdentifier.parse("review:914").refined(),
            )
        val reference = ModelRuleReference(modelIdentity, ModelIdentifier.parse("server-client").refined())
        val model =
            BoundaryModel.Continuation.admit(reference, source.reference, target.reference, source, target, emptySet())
                .refined()
        val connection = BoundaryArrival.connect(source, model).refined()
        val terminal =
            QueryImpactTerminal.Unresolved.Boundary(
                BoundaryArrival.unresolved(target, BoundaryUnresolvedReason.MISSING_CONSUMER)
            )
        val path =
            QueryImpactPath.fromEvidence(
                    producer,
                    listOf(QueryImpactStep.ModeledBoundary(connection)),
                    QueryImpactRepresentation.NotModeled,
                    terminal,
                )
                .refined()
        assertEquals(producer.basis, path.producerBasis)
        assertEquals(destination.basis, path.destinationBasis)
        assertNotEquals(path.producerBasis, path.destinationBasis)
        assertEquals(setOf(BoundaryRequiredEvidence.EXACT_DOWNSTREAM_POSITION), terminal.boundary.obligation.required)
    }

    private class Fixture(root: String = "/workspace", generation: Long = 1) {
        private val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root)).refined(),
                EvidenceGeneration.parse(generation).refined(),
            )
        private val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    "owner",
                    lease,
                    Path.of("$root/File.kt"),
                    "file://$root/File.kt",
                    0,
                )
                .refined()
        private val file = (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file
        private val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    0,
                    100,
                    "owner",
                    "fixture.owner",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("fixture.owner", null, emptyList(), emptyList(), 0).refined(),
                )
                .refined()
        private val owner =
            RelationEndpoint.resolve(
                    lease,
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.INCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                    evidence,
                )
                .refined()
        val domain =
            RelationRequest.start(
                SymbolSelector.issue(lease, owner.scope, evidence),
                RelationMeaning.References,
                RelationBudget(
                    io.github.amichne.kast.kernel.ResourceBudget(
                        io.github.amichne.kast.kernel.ResultLimit.parse(10).refined(),
                        io.github.amichne.kast.kernel.WorkUnitLimit.parse(100).refined(),
                        io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(1000).refined(),
                    ),
                    RelationByteLimit.parse(100000).refined(),
                ),
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )

        fun site(offset: Int, role: ValueRole) =
            ValueSite.fromCompiler(owner, ExactDeclarationTextRange.parse(offset, offset + 1).refined(), role).refined()

        fun terminal(site: ValueSite) =
            QueryImpactTerminal.SupportedDomainEnd.admit(
                    ValueFlowStep.fromCompiler(
                            site,
                            emptyList(),
                            emptyList(),
                            ValueFlowTerminal.SupportedDomainExhausted,
                            domain,
                            RelationWorkCount.parse(1).refined(),
                        )
                        .refined()
                )
                .refined()
    }
}

private fun <S, F> Refinement<S, F>.refined(): S =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
