package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.runtime.server.RuntimeServer
import io.github.amichne.kast.runtime.server.RuntimeServerConstruction
import io.github.amichne.kast.runtime.server.ServerDispatch
import io.github.amichne.kast.runtime.server.TypedOperationBinding
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class HostedPublicReadProtocolTest {
    @Test
    fun `relation and diagnostic reads dispatch through the hosted public runtime`() = runTest {
        val fixture = HostedMutationProtocolFixture()
        val selectorToken = ProtocolText.parse("exact:v1:11:1").refined()
        val selectors = object : HostedExactSelectorOperations {
            override fun issueExact(selector: io.github.amichne.kast.symbol.contract.SymbolSelector) =
                HostedExactIssuance.Rejected

            override suspend fun exact(token: ProtocolText) =
                if (token == selectorToken) HostedExactLookup.Found(fixture.selector)
                else HostedExactLookup.Missing
        }
        val relations = RelationOperations { request ->
            val batch = RelationBatch.create(
                request,
                emptyList(),
                RelationByteCount.parse(0L).refined(),
                RelationWorkCount.parse(0L).refined(),
            ).refined()
            RelationCompilation.complete(batch).let { complete ->
                RelationReadResult.Complete(complete.batch, complete.coverage)
            }
        }
        val diagnostics = DiagnosticOperations { request ->
            DiagnosticCompilation.complete(DiagnosticBatch.empty(request.scope)).let { complete ->
                io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult.Complete(
                    complete.batch,
                    complete.coverage,
                )
            }
        }
        val server = RuntimeServer.createHostedEffects(
            HostedTopologyProtocol.bindings(rejectedTopology, selectors, relations) +
                HostedDiagnosticProtocol.bindings(
                    HostedWorkspaceOperations(fixture.workspace),
                    diagnostics,
                    selectors,
                ) + HostedIndexSyncProtocol.bindings {
                    io.github.amichne.kast.workspace.contract.IndexSynchronizationResult.Rejected(
                        io.github.amichne.kast.workspace.contract.IndexSynchronizationFailure.WorkspaceNotReady,
                    )
                } + rejectingMutationBindings(),
        ).created()

        val relation = server.dispatch(
            CanonicalOperationWireBindings.relationRead.encodeRequest(
                RelationReadRequest(
                    selectorToken,
                    RelationKindDocument.REFERENCES,
                    ProtocolCount.parse(8).refined(),
                ),
            ).encoded(),
        ).responded()
        val diagnostic = server.dispatch(
            CanonicalOperationWireBindings.diagnosticCheck.encodeRequest(
                DiagnosticCheckRequest(
                    ProtocolText.parse("app/src/main/kotlin/sample/Service.kt").refined(),
                    ProtocolCount.parse(8).refined(),
                ),
            ).encoded(),
        ).responded()

        when (val outcome = CanonicalOperationWireBindings.relationRead.decodeOutcome(relation).decoded()) {
            is OperationOutcome.Complete -> {
                assertEquals(CanonicalOperation.RELATION_READ.id, outcome.evidence.operation)
                assertEquals(fixture.workspace.readLease.generation, outcome.evidence.generation)
                assertEquals(emptyList<Any>(), outcome.evidence.payload.relations.values)
            }
            is OperationOutcome.Qualified -> fail("expected complete relation evidence")
            is OperationOutcome.Rejected -> fail("unexpected relation rejection: ${outcome.reason}")
        }
        when (val outcome = CanonicalOperationWireBindings.diagnosticCheck.decodeOutcome(diagnostic).decoded()) {
            is OperationOutcome.Complete -> {
                assertEquals(CanonicalOperation.DIAGNOSTIC_CHECK.id, outcome.evidence.operation)
                assertEquals(fixture.workspace.readLease.generation, outcome.evidence.generation)
                assertEquals(emptyList<Any>(), outcome.evidence.payload.diagnostics.values)
            }
            is OperationOutcome.Qualified -> fail("expected complete diagnostic evidence")
            is OperationOutcome.Rejected -> fail("unexpected diagnostic rejection: ${outcome.reason}")
        }
    }

    private fun rejectingMutationBindings() = listOf(
        TypedOperationBinding(
            CanonicalOperationWireBindings.changePlan,
            OperationHandler { OperationOutcome.Rejected(ChangePlanRejection.WORKSPACE_NOT_READY) },
        ),
        TypedOperationBinding(
            CanonicalOperationWireBindings.changeApply,
            OperationHandler { OperationOutcome.Rejected(ChangeApplyRejection.PLAN_NOT_FOUND) },
        ),
        TypedOperationBinding(
            CanonicalOperationWireBindings.changeVerify,
            OperationHandler { OperationOutcome.Rejected(ChangeVerifyRejection.APPLICATION_NOT_FOUND) },
        ),
        TypedOperationBinding(
            CanonicalOperationWireBindings.changeRecover,
            OperationHandler { OperationOutcome.Rejected(ChangeRecoverRejection.PLAN_NOT_FOUND) },
        ),
    )

    private fun RuntimeServerConstruction.created(): RuntimeServer = when (this) {
        is RuntimeServerConstruction.Created -> server
        is RuntimeServerConstruction.Rejected -> error(failures.toString())
    }

    private fun WireEncoding.encoded(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error(failure.toString())
    }

    private fun ServerDispatch.responded(): String = when (this) {
        is ServerDispatch.Responded -> document
        is ServerDispatch.Rejected -> error(failure.toString())
    }

    private fun <Value> WireDecoding<Value>.decoded(): Value = when (this) {
        is WireDecoding.Decoded -> value
        is WireDecoding.Rejected -> error(failure.toString())
    }

    private companion object {
        val rejectedTopology = HostedTopologyOperations(
            TopologyBuildOperations {
                TopologyBuildResult.Rejected(TopologyBuildFailure.WorkspaceNotReady)
            },
            TraversalOperations {
                TraversalResult.Rejected(TraversalRejection.RequiredEvidenceUnavailable)
            },
        )
    }
}
