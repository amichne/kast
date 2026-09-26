package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeFilePreview
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewKind
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewSet
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangePreviewDiff
import io.github.amichne.kast.protocol.contract.ChangePreviewPath
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRecoveryDocumentState
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.contract.TopologyBuildDigest
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyBuildStatus
import io.github.amichne.kast.protocol.contract.TopologyExtractionRejection
import io.github.amichne.kast.protocol.registry.CanonicalOperationDefinitions
import io.github.amichne.kast.protocol.registry.HostedVariants
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
    fun `generated registry document preserves the typed definition order`() {
        val operations =
            CanonicalOperationDefinitions.registry.definitions.joinToString(",") {
                val intents =
                    when (val variants = it.hostedVariants) {
                        is HostedVariants.Intents ->
                            variants.intents.joinToString(",") { intent ->
                                "\"${intent.identity}\""
                            }
                        HostedVariants.None -> ""
                    }
                "{\"operationId\":\"${it.id.value}\",\"hostedExposure\":" +
                    "\"${it.hostedExposure.name.lowercase()}\",\"intents\":[$intents]}"
            }

        assertEquals(
            "{\"schemaVersion\":2,\"operations\":[$operations]}\n",
            CanonicalOperationWireBindings.operationRegistryDocument,
        )
    }

    @Test
    fun `every change prerequisite rejection round trips`() {
        assertRejections(
            CanonicalOperationWireBindings.changePlan,
            ChangePlanRejection.entries,
        )
    }

    @Test
    fun `all production bindings round trip requests and every outcome variant`() {
        assertRoundTrips(
            CanonicalOperationWireBindings.diagnosticCheck,
            DiagnosticCheckRequest(text("project:fixture"), count(100)),
            DiagnosticCheckResult(
                BoundedProtocolList.create(
                        listOf(
                            DiagnosticDocument(
                                DiagnosticSeverityDocument.WARNING,
                                text("UNUSED"),
                                text("unused"),
                                DiagnosticLocationDocument(
                                    text("candidate:diagnostic"),
                                    text("src/Target.kt"),
                                    DiagnosticRangeDocument.create(offset(3), offset(3)).refinedValue(),
                                ),
                            )
                        )
                    )
                    .refinedValue()
            ),
            diagnosticQualification(),
            DiagnosticCheckRejection.SCOPE_REJECTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.topologyBuild,
            TopologyBuildRequest,
            TopologyBuildResult(
                TopologyBuildStatus.PUBLISHED,
                EvidenceGeneration.parse(17).refinedValue(),
                TopologyBuildDigest.parse("a".repeat(64)).refinedValue(),
            ),
            TopologyBuildQualification.PROGRESS_UNAVAILABLE,
            TopologyBuildRejection.ExtractionFailed(
                text("topology/intellij/src/main/kotlin/TopologyK2Projection.kt"),
                TopologyExtractionRejection.VFS_CONTENT_MISMATCH,
            ),
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changePlan,
            ChangePlanRequest(
                ChangeIntentDocument.AddDeclaration(
                    text("exact:Target"),
                    text("fun added() = Unit"),
                )
            ),
            ChangePlanResult(text("plan:1"), preview()),
            ChangePlanQualification.OPTIONAL_EVIDENCE_INCOMPLETE,
            ChangePlanRejection.RELATION_READ_REQUIRED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changeApply,
            ChangeApplyRequest(text("plan:1")),
            ChangeApplyResult(text("receipt:1"), preview()),
            ChangeApplyQualification.RECOVERY_REQUIRED,
            ChangeApplyRejection.CONTENT_CHANGED,
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
        val intents =
            listOf(
                ChangeIntentDocument.AddFile(text("src/New.kt"), text("class New")),
                ChangeIntentDocument.AddDeclaration(text("exact:Target"), text("fun added() = Unit")),
                ChangeIntentDocument.ReplaceDeclaration(text("exact:Target"), text("class Target")),
                ChangeIntentDocument.RenameSymbol(text("exact:Target"), text("Renamed")),
            )

        intents.forEach { intent ->
            val request = ChangePlanRequest(intent)
            val encoded = CanonicalOperationWireBindings.changePlan.encodeRequest(request).encodedDocument()
            val admitted = WireRequestEnvelope.admit(encoded).admittedRequest()
            assertEquals(
                WireDecoding.Decoded(request),
                CanonicalOperationWireBindings.changePlan.decodeRequest(admitted),
            )
        }
    }

    @Test
    fun `production binding rejects unknown operation schema and invalid refined payload`() {
        val binding = CanonicalOperationWireBindings.diagnosticCheck
        val encoded =
            binding.encodeRequest(DiagnosticCheckRequest(text("project:fixture"), count(100))).encodedDocument()
        val unknownSchema = SchemaIdentity.parse("kast.unknown.v1").refinedValue()
        val unknownOperation = OperationId.parse("symbol.missing").refinedValue()

        val admittedUnknownSchema =
            WireRequestEnvelope.admit(encoded.replace(binding.schema.value, unknownSchema.value)).admittedRequest()
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
                )
            ),
        )

        val invalidPayload = encoded.replace("\"project:fixture\"", "\"\"")
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

        val evidence =
            EvidenceEnvelope(
                operation = binding.operation.id,
                generation = EvidenceGeneration.parse(17).refinedValue(),
                payload = result,
            )
        listOf(
                OperationOutcome.Complete(evidence),
                OperationOutcome.Qualified(evidence, qualification),
                OperationOutcome.Rejected(rejection),
            )
            .forEach { outcome ->
                val document = binding.encodeOutcome(outcome).encodedDocument()
                assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(document))
            }
    }

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
    > assertRejections(
        binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
        rejections: Iterable<Rejection>,
    ) {
        rejections.forEach { rejection ->
            val outcome = OperationOutcome.Rejected(rejection)
            val document = binding.encodeOutcome(outcome).encodedDocument()
            assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(document))
        }
    }

    private fun diagnosticQualification(): DiagnosticCheckQualification =
        DiagnosticCheckQualification.create(
                DiagnosticKnownCountDocument.parse(1).refinedValue(),
                resultLimitReached = true,
                analyzedFiles = listOf(text("src/Target.kt")),
                limitations =
                    listOf(
                        DiagnosticLimitationDocument(text("src/Other.kt"), DiagnosticLimitationReasonDocument.INDEXING)
                    ),
            )
            .refinedValue()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refinedValue()

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refinedValue()

    private fun preview(): ChangeFilePreviewSet =
        ChangeFilePreviewSet.admit(
                listOf(
                    ChangeFilePreview(
                        ChangePreviewPath.parse("src/Target.kt").refinedValue(),
                        ChangeFilePreviewKind.UPDATE,
                        ChangePreviewDiff.parse("-old\n+new").refinedValue(),
                    )
                )
            )
            .refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refined value, got $failure")
        }

    private fun WireEncoding.encodedDocument(): String =
        when (this) {
            is WireEncoding.Encoded -> document
            is WireEncoding.Rejected -> error("Expected encoded document, got $failure")
        }

    private fun WireRequestAdmission.admittedRequest(): AdmittedWireRequest =
        when (this) {
            is WireRequestAdmission.Admitted -> request
            is WireRequestAdmission.Rejected -> error("Expected admitted request, got $failure")
        }
}
