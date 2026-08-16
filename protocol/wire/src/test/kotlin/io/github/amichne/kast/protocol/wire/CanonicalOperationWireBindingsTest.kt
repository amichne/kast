package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalOperationWireBindingsTest {
    @Test
    fun `production serializer table covers the exact canonical operation set`() {
        assertEquals(
            CanonicalOperation.entries,
            CanonicalOperationWireBindings.table.bindings.map { it.operation },
        )
    }

    @Test
    fun `all production bindings round trip requests and every outcome variant`() {
        assertRoundTrips(
            CanonicalOperationWireBindings.workspaceInspect,
            WorkspaceInspectRequest,
            WorkspaceInspectResult(text("/fixture"), WorkspaceStateDocument.READY),
            WorkspaceInspectQualification.RECONCILING,
            WorkspaceInspectRejection.RUNTIME_BLOCKED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.symbolDiscover,
            SymbolDiscoverRequest(text("Target"), count(20)),
            SymbolDiscoverResult(texts("candidate:Target")),
            SymbolDiscoverQualification.RESULT_LIMIT,
            SymbolDiscoverRejection.QUERY_REJECTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.symbolResolve,
            SymbolResolveRequest(text("candidate:Target")),
            SymbolResolveResult(text("exact:Target")),
            SymbolResolveQualification.EVIDENCE_INCOMPLETE,
            SymbolResolveRejection.AMBIGUOUS,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.symbolDescribe,
            SymbolDescribeRequest(text("exact:Target")),
            SymbolDescribeResult(text("class Target")),
            SymbolDescribeQualification.EVIDENCE_INCOMPLETE,
            SymbolDescribeRejection.SELECTOR_STALE,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.relationRead,
            RelationReadRequest(text("exact:Target"), RelationKindDocument.CALLERS, count(50)),
            RelationReadResult(texts("exact:Caller")),
            RelationReadQualification.COVERAGE_INCOMPLETE,
            RelationReadRejection.RELATION_UNSUPPORTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.traversalRun,
            TraversalRunRequest(
                text("exact:Target"),
                RelationKindDocument.CALLERS,
                count(3),
                count(100),
            ),
            TraversalRunResult(texts("exact:Caller", "exact:Root")),
            TraversalRunQualification.DEPTH_LIMIT,
            TraversalRunRejection.PLAN_REJECTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.diagnosticCheck,
            DiagnosticCheckRequest(text("project:fixture"), count(100)),
            DiagnosticCheckResult(texts("warning:unused")),
            DiagnosticCheckQualification.RESULT_LIMIT,
            DiagnosticCheckRejection.SCOPE_REJECTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changePlan,
            ChangePlanRequest(
                ChangeIntentDocument.AddDeclaration(
                    text("exact:Target"),
                    text("fun added() = Unit"),
                ),
            ),
            ChangePlanResult(text("plan:1")),
            ChangePlanQualification.OPTIONAL_EVIDENCE_INCOMPLETE,
            ChangePlanRejection.REQUIRED_EVIDENCE_INCOMPLETE,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changeApply,
            ChangeApplyRequest(text("plan:1")),
            ChangeApplyResult(text("application:1")),
            ChangeApplyQualification.RECOVERY_REQUIRED,
            ChangeApplyRejection.CONTENT_CHANGED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changeVerify,
            ChangeVerifyRequest(text("application:1")),
            ChangeVerifyResult(text("receipt:1")),
            ChangeVerifyQualification.PROOF_INCOMPLETE,
            ChangeVerifyRejection.SEMANTIC_DELTA_REJECTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changeRecover,
            ChangeRecoverRequest(text("plan:1")),
            ChangeRecoverResult(ChangeRecoveryDocumentState.ROLLED_BACK),
            ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED,
            ChangeRecoverRejection.RECOVERY_FAILED,
        )
    }

    @Test
    fun `all four closed change intents use the one production plan binding`() {
        val intents = listOf(
            ChangeIntentDocument.AddFile(text("src/New.kt"), text("class New")),
            ChangeIntentDocument.AddDeclaration(text("exact:Target"), text("fun added() = Unit")),
            ChangeIntentDocument.ReplaceDeclaration(text("exact:Target"), text("class Target")),
            ChangeIntentDocument.RenameSymbol(text("exact:Target"), text("Renamed")),
        )

        intents.forEach { intent ->
            val request = ChangePlanRequest(intent)
            val encoded = CanonicalOperationWireBindings.changePlan.encodeRequest(request)
                .encodedDocument()
            val admitted = WireRequestEnvelope.admit(encoded).admittedRequest()
            assertEquals(
                WireDecoding.Decoded(request),
                CanonicalOperationWireBindings.changePlan.decodeRequest(admitted),
            )
        }
    }

    @Test
    fun `production binding rejects unknown operation schema and invalid refined payload`() {
        val binding = CanonicalOperationWireBindings.symbolDiscover
        val encoded = binding.encodeRequest(SymbolDiscoverRequest(text("Target"), count(20)))
            .encodedDocument()
        val unknownSchema = SchemaIdentity.parse("kast.unknown.v1").refinedValue()
        val unknownOperation = OperationId.parse("symbol.missing").refinedValue()

        val admittedUnknownSchema = WireRequestEnvelope.admit(
            encoded.replace(binding.schema.value, unknownSchema.value),
        ).admittedRequest()
        assertEquals(
            WireDecoding.Rejected(WireFailure.UnknownSchema(unknownSchema)),
            binding.decodeRequest(admittedUnknownSchema),
        )
        assertEquals(
            WireRequestAdmission.Rejected(WireFailure.UnknownOperation(unknownOperation)),
            WireRequestEnvelope.admit(
                encoded.replace(
                    "\"operation\":\"${binding.operation.id.value}\"",
                    "\"operation\":\"${unknownOperation.value}\"",
                ),
            ),
        )

        val invalidPayload = encoded.replace("\"Target\"", "\"\"")
        assertEquals(
            WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
            binding.decodeRequest(WireRequestEnvelope.admit(invalidPayload).admittedRequest()),
        )
    }

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
        > assertRoundTrips(
        binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
        request: Request,
        result: Result,
        qualification: Qualification,
        rejection: Rejection,
    ) {
        val requestDocument = binding.encodeRequest(request).encodedDocument()
        val admittedRequest = WireRequestEnvelope.admit(requestDocument).admittedRequest()
        assertEquals(WireDecoding.Decoded(request), binding.decodeRequest(admittedRequest))

        val evidence = EvidenceEnvelope(
            operation = binding.operation.id,
            generation = EvidenceGeneration.parse(17).refinedValue(),
            payload = result,
        )
        listOf(
            OperationOutcome.Complete(evidence),
            OperationOutcome.Qualified(evidence, qualification),
            OperationOutcome.Rejected(rejection),
        ).forEach { outcome ->
            val document = binding.encodeOutcome(outcome).encodedDocument()
            assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(document))
        }
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refinedValue()

    private fun texts(vararg raw: String): BoundedProtocolList<ProtocolText> =
        BoundedProtocolList.create(raw.map(::text)).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error("Expected encoded document, got $failure")
    }

    private fun WireRequestAdmission.admittedRequest(): AdmittedWireRequest = when (this) {
        is WireRequestAdmission.Admitted -> request
        is WireRequestAdmission.Rejected -> error("Expected admitted request, got $failure")
    }
}
