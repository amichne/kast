package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRequiredDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryUnresolvedDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionStopDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowEndObservationDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowReadPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationCurrentDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationUnknownDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.query.contract.QueryImpactExecutionStop
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryUnresolvedReason
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryImpactPathProjectionTest {
    @Test
    fun `consumer projection retains different current state and complete expected rule separately`() {
        val fixture = ImpactPathProjectionFixture()
        val path = consumerPath(fixture)
        val encoded =
            Json.encodeToJsonElement(ImpactPathDocument.serializer(), path.impactDocument().refined()).jsonObject
        assertEquals(setOf("producer", "steps", "representation", "terminal"), encoded.keys)
        assertEquals(
            "COMPILER",
            encoded.getValue("steps").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content,
        )
        val representation = encoded.getValue("representation").jsonObject
        assertEquals("PRESENT", representation.getValue("type").jsonPrimitive.content)
        val branch = representation.getValue("branches").jsonArray.single().jsonObject
        assertEquals(
            "HIPED",
            branch.getValue("current").jsonObject.getValue("state").jsonObject.getValue("state").jsonPrimitive.content,
        )
        assertEquals(
            listOf("ORIGIN", "COMPILER_TRANSFER"),
            branch.getValue("history").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        val terminal = encoded.getValue("terminal").jsonObject
        assertEquals("CONSUMER", terminal.getValue("type").jsonPrimitive.content)
        assertEquals("DIFFERENT", terminal.getValue("outcome").jsonPrimitive.content)
        assertEquals("PLAINTEXT", terminal.getValue("rule").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("consumer", terminal.getValue("reference").jsonObject.getValue("rule").jsonPrimitive.content)
        assertEquals(
            fixture.call.callable.compilerIdentity.value,
            terminal
                .getValue("rule")
                .jsonObject
                .getValue("input")
                .jsonObject
                .getValue("declaration")
                .jsonObject
                .getValue("compilerIdentity")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `modeled boundary keeps two bases obligations and unknown current without erasing origin`() {
        val fixture = ImpactPathProjectionFixture()
        val path = boundaryPath(fixture)
        val projected = path.impactDocument().refined()
        val current =
            (projected.representation as ImpactRepresentationEvidenceDocument.Present).branches.values.single().current
        assertEquals(
            ImpactRepresentationCurrentDocument.Unknown(
                ImpactRepresentationUnknownDocument.BOUNDARY_PRESERVATION_UNPROVEN
            ),
            current,
        )
        val connection = (projected.steps.values.single() as ImpactPathStepDocument.ModeledBoundary).connection
        assertEquals(
            "/workspace",
            (connection.rule.source.site.enclosing.basis as ImpactSemanticBasisDocument.Published).root.value,
        )
        assertEquals(
            "/client",
            (connection.rule.target.site.enclosing.basis as ImpactSemanticBasisDocument.Published).root.value,
        )
        assertEquals(
            9,
            (connection.rule.target.site.enclosing.basis as ImpactSemanticBasisDocument.Published).generation.value,
        )
        assertEquals(
            setOf(
                ImpactBoundaryRequiredDocument.RETENTION_POLICY,
                ImpactBoundaryRequiredDocument.DECODING_COMPATIBILITY,
                ImpactBoundaryRequiredDocument.MIGRATION_PROOF,
            ),
            connection.obligations.values.single().required.values.toSet(),
        )
        assertEquals(
            ImpactBoundaryUnresolvedDocument.MISSING_CONSUMER,
            (projected.terminal as ImpactPathTerminalDocument.UnresolvedBoundary).reason,
        )
    }

    @Test
    fun `supported terminal projects exact original domain and measured work without complete field`() {
        val fixture = ImpactPathProjectionFixture()
        val budget =
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(8).refined(),
                    WorkUnitLimit.parse(13).refined(),
                    ElapsedTimeLimitMillis.parse(21).refined(),
                ),
                RelationByteLimit.parse(100000).refined(),
            )
        val domain =
            RelationRequest.start(
                fixture.owner as RelationEndpoint.Resolved,
                RelationMeaning.References,
                budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        val observation =
            ValueFlowStep.fromCompiler(
                    fixture.producer,
                    emptyList(),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    domain,
                    RelationWorkCount.parse(5).refined(),
                )
                .refined()
        val path =
            QueryImpactPath.fromEvidence(
                    fixture.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(observation).refined(),
                )
                .refined()
        val projected =
            (path.impactDocument().refined().terminal as ImpactPathTerminalDocument.SupportedDomainEnd).observation
        assertEquals(domain.scopeFingerprint.value, projected.domain.fingerprint.value)
        assertEquals(QueryRelationRequestedDomainDocument.WORKSPACE, projected.domain.requestedDomain)
        assertEquals(5, projected.examinedWorkUnits.value)
        assertEquals(observation.retainedBytes, projected.retainedBytes.value)
        assertEquals(13, projected.domain.budget.maxWorkUnits.value)
        assertEquals(ImpactFlowReadPositionDocument.Start, projected.domain.position)
        val encoded = Json.encodeToJsonElement(ImpactFlowEndObservationDocument.serializer(), projected).jsonObject
        assertEquals(setOf("source", "domain", "examinedWorkUnits", "retainedBytes", "terminal"), encoded.keys)
        assertEquals("SUPPORTED_DOMAIN_EXHAUSTED", encoded.getValue("terminal").jsonPrimitive.content)
    }

    @Test
    fun `rejected reads preserve every native and contract cause with exact site and requested domain`() {
        val fixture = ImpactPathProjectionFixture()
        for (cause in ValueFlowRejection.entries) {
            val rejected =
                QueryImpactReadRejection.Native(
                    fixture.producer,
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                    cause,
                    RelationWorkCount.parse(3).refined(),
                )
            val path =
                QueryImpactPath.fromEvidence(
                        fixture.producer,
                        emptyList(),
                        QueryImpactRepresentation.NotModeled,
                        QueryImpactTerminal.Unresolved.ReadRejected(rejected),
                    )
                    .refined()
            val terminal = path.impactDocument().refined().terminal as ImpactPathTerminalDocument.UnresolvedRead
            val evidence = terminal.rejection as ImpactReadRejectionDocument.Native
            assertEquals(fixture.producer.impactDocument().refined(), evidence.source)
            assertEquals(ImpactRequestedBoundaryDocument.Workspace, evidence.domain)
            assertEquals(cause.name, evidence.cause.name)
            assertEquals(3L, evidence.examinedWorkUnits.value)
        }
        for (cause in ValueFlowStepFailure.entries) {
            val rejected =
                QueryImpactReadRejection.Contract(
                    fixture.producer,
                    RelationSearchBoundary.RETAINED_SUBJECT,
                    cause,
                    RelationWorkCount.parse(2).refined(),
                )
            val projected = rejected.impactDocument().refined() as ImpactReadRejectionDocument.Contract
            assertEquals(ImpactRequestedBoundaryDocument.RetainedSeed, projected.domain)
            assertEquals(cause.name, projected.cause.name)
            assertEquals(2L, projected.examinedWorkUnits.value)
        }
    }

    @Test
    fun `cycle and checkpoint stops retain positive ordered route and exact capacity witnesses`() {
        val fixture = ImpactPathProjectionFixture()
        val second = fixture.site(50, 51, ValueRole.ExpressionResult)
        val route =
            listOf(
                QueryImpactStep.Compiler(
                    ValueTransfer.fromCompiler(fixture.producer, second, ValueTransferKind.BRANCH_ALTERNATIVE).refined()
                ),
                QueryImpactStep.Compiler(
                    ValueTransfer.fromCompiler(second, fixture.producer, ValueTransferKind.BRANCH_ALTERNATIVE).refined()
                ),
            )
        val cycle = QueryImpactExecutionStop.Cycle.admit(fixture.producer, route).refined()
        val path =
            QueryImpactPath.fromEvidence(
                    fixture.producer,
                    route,
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.ExecutionStop(cycle),
                )
                .refined()
        val projected =
            (path.impactDocument().refined().terminal as ImpactPathTerminalDocument.ExecutionStop).stop
                as ImpactExecutionStopDocument.Cycle
        assertEquals(0L, projected.repeatedAt.value)
        assertEquals(route.map { it.impactDocument().refined() }, projected.prefix.values)
        assertEquals(fixture.producer.impactDocument().refined(), projected.source)
        val capacity =
            QueryImpactExecutionStop.CheckpointCapacity.admit(
                    fixture.producer,
                    RelationByteCount.parse(2048).refined(),
                    RelationByteLimit.parse(1024).refined(),
                )
                .refined()
        val cut = capacity.impactDocument().refined() as ImpactExecutionStopDocument.CheckpointCapacity
        assertEquals(2048L, cut.requiredBytes.value)
        assertEquals(1024L, cut.availableBytes.value)
    }

    private fun consumerPath(fixture: ImpactPathProjectionFixture): QueryImpactPath {
        val input = fixture.site(31, 39, ValueRole.Argument(fixture.call, ValueArgumentPosition.parse(0).refined()))
        val transfer = ValueTransfer.fromCompiler(fixture.producer, input, ValueTransferKind.ARGUMENT).refined()
        val arrived = fixture.origin.transfer(transfer).refined()
        val rule =
            RepresentationRule.ConsumerExpectation.admit(
                    fixture.rule("consumer"),
                    fixture.bind(ModelValuePosition.Argument(ValueArgumentPosition.parse(0).refined())),
                    fixture.domain.state(fixture.id("PLAINTEXT")).refined(),
                )
                .refined()
        return QueryImpactPath.fromEvidence(
                fixture.producer,
                listOf(QueryImpactStep.Compiler(transfer)),
                QueryImpactRepresentation.Present(arrived),
                QueryImpactTerminal.Consumer(arrived.expect(rule).refined()),
            )
            .refined()
    }

    private fun boundaryPath(fixture: ImpactPathProjectionFixture): QueryImpactPath {
        val other = ImpactPathProjectionFixture("/client", 9)
        val source = fixture.boundary(fixture.producer)
        val target = other.boundary(other.producer)
        val model =
            BoundaryModel.Continuation.admit(
                    fixture.rule("wire"),
                    source.reference,
                    target.reference,
                    source,
                    target,
                    emptySet(),
                )
                .refined()
        val connected = BoundaryArrival.connect(source, model).refined()
        val arrived = fixture.origin.throughBoundary(connected).refined()
        val unresolved = BoundaryArrival.unresolved(target, BoundaryUnresolvedReason.MISSING_CONSUMER)
        return QueryImpactPath.fromEvidence(
                fixture.producer,
                listOf(QueryImpactStep.ModeledBoundary(connected)),
                QueryImpactRepresentation.Present(arrived),
                QueryImpactTerminal.Unresolved.Boundary(unresolved),
            )
            .refined()
    }

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
