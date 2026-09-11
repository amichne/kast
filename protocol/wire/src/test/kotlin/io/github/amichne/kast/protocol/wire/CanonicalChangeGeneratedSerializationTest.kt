package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
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
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalChangeGeneratedSerializationTest {
    @Test
    fun `generated documents own every change payload serializer`() {
        val serializers =
            listOf<KSerializer<*>>(
                ChangePlanRequest.serializer(),
                ChangeIntentDocument.serializer(),
                ChangePlanResultDocument.serializer(),
                ChangePlanQualificationDocument.serializer(),
                ChangePlanRejectionDocument.serializer(),
                ChangeApplyRequest.serializer(),
                ChangeApplyResultDocument.serializer(),
                ChangeApplyQualificationDocument.serializer(),
                ChangeApplyRejectionDocument.serializer(),
                ChangeRecoverRequest.serializer(),
                ChangeRecoverResultDocument.serializer(),
                ChangeRecoveryStateDocument.serializer(),
                ChangeRecoverQualificationDocument.serializer(),
                ChangeRecoverRejectionDocument.serializer(),
            )

        assertEquals(14, serializers.map { it.descriptor.serialName }.distinct().size)
    }

    @Test
    fun `generated plan request preserves the exact four intent shapes`() {
        val cases =
            listOf(
                ChangeIntentDocument.AddFile(text("src/New.kt"), text("class New")) to
                    """{"intent":{"kind":"add-file","relativePath":"src/New.kt","content":"class New"}}""",
                ChangeIntentDocument.AddDeclaration(text("exact:Target"), text("fun added() = Unit")) to
                    """{"intent":{"kind":"add-declaration","exactTarget":"exact:Target",""" +
                        """"declaration":"fun added() = Unit"}}""",
                ChangeIntentDocument.ReplaceDeclaration(text("exact:Target"), text("class Target")) to
                    """{"intent":{"kind":"replace-declaration","exactTarget":"exact:Target",""" +
                        """"replacement":"class Target"}}""",
                ChangeIntentDocument.RenameSymbol(text("exact:Target"), text("Renamed")) to
                    """{"intent":{"kind":"rename-symbol","exactTarget":"exact:Target","newName":"Renamed"}}""",
            )

        cases.forEach { (intent, expected) ->
            assertEquals(
                wireJson.parseToJsonElement(expected),
                CanonicalOperationWireBindings.changePlan.requestPayload(ChangePlanRequest(intent)),
            )
        }
    }

    @Test
    fun `generated documents preserve exact change scalar payload shapes`() {
        assertEquals(
            wireJson.parseToJsonElement(
                """{"planIdentity":"plan:1","changes":[{"path":"src/Target.kt","kind":"update","diff":"-old\n+new"}]}"""
            ),
            CanonicalOperationWireBindings.changePlan.resultPayload(ChangePlanResult(text("plan:1"), preview())),
        )
        assertEquals(
            wireJson.parseToJsonElement("""{"planIdentity":"plan:1"}"""),
            CanonicalOperationWireBindings.changeApply.requestPayload(ChangeApplyRequest(text("plan:1"))),
        )
        assertEquals(
            wireJson.parseToJsonElement(
                """{"state":"verified","receiptIdentity":"receipt:1","changes":[{"path":"src/Target.kt",""" +
                    """"kind":"update","diff":"-old\n+new"}]}"""
            ),
            CanonicalOperationWireBindings.changeApply.resultPayload(ChangeApplyResult(text("receipt:1"), preview())),
        )
        assertEquals(
            wireJson.parseToJsonElement("""{"planIdentity":"plan:1"}"""),
            CanonicalOperationWireBindings.changeRecover.requestPayload(ChangeRecoverRequest(text("plan:1"))),
        )
        assertEquals(
            wireJson.parseToJsonElement("""{"state":"rolled_back"}"""),
            CanonicalOperationWireBindings.changeRecover.resultPayload(
                ChangeRecoverResult(ChangeRecoveryDocumentState.ROLLED_BACK)
            ),
        )
    }

    @Test
    fun `generated enum documents preserve established primitive spellings`() {
        assertEquals(
            JsonPrimitive("optional_evidence_incomplete"),
            CanonicalOperationWireBindings.changePlan.qualificationPayload(
                ChangePlanResult(text("plan:1"), preview()),
                ChangePlanQualification.OPTIONAL_EVIDENCE_INCOMPLETE,
            ),
        )
        assertEquals(
            JsonPrimitive("relation_read_required"),
            CanonicalOperationWireBindings.changePlan.rejectionPayload(ChangePlanRejection.RELATION_READ_REQUIRED),
        )
        assertEquals(
            JsonPrimitive("recovery_required"),
            CanonicalOperationWireBindings.changeApply.qualificationPayload(
                ChangeApplyResult(text("receipt:1"), preview()),
                ChangeApplyQualification.RECOVERY_REQUIRED,
            ),
        )
        assertEquals(
            JsonPrimitive("content_changed"),
            CanonicalOperationWireBindings.changeApply.rejectionPayload(ChangeApplyRejection.CONTENT_CHANGED),
        )
        assertEquals(
            JsonPrimitive("semantic_delta_rejected"),
            CanonicalOperationWireBindings.changeApply.rejectionPayload(ChangeApplyRejection.SEMANTIC_DELTA_REJECTED),
        )
        assertEquals(
            JsonPrimitive("manual_recovery_required"),
            CanonicalOperationWireBindings.changeRecover.qualificationPayload(
                ChangeRecoverResult(ChangeRecoveryDocumentState.RECOVERY_REQUIRED),
                ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED,
            ),
        )
        assertEquals(
            JsonPrimitive("recovery_failed"),
            CanonicalOperationWireBindings.changeRecover.rejectionPayload(ChangeRecoverRejection.RECOVERY_FAILED),
        )
    }

    @Test
    fun `generated plan request rejects unknown missing and unrefined input`() {
        val binding = CanonicalOperationWireBindings.changePlan
        val encoded =
            binding
                .encodeRequest(ChangePlanRequest(ChangeIntentDocument.AddFile(text("src/New.kt"), text("class New"))))
                .encodedDocument()
        val malformed =
            listOf(
                encoded.replace("\"content\":\"class New\"", "\"content\":\"class New\",\"extra\":true"),
                encoded.replace(",\"content\":\"class New\"", ""),
                encoded.replace("add-file", "unknown-intent"),
                encoded.replace("src/New.kt", ""),
            )

        malformed.forEach { document ->
            val request = WireRequestEnvelope.admit(document).admittedRequest()
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
                binding.decodeRequest(request),
            )
        }
    }

    @Test
    fun `unverified and recovery effects round trip without manufacturing a receipt`() {
        val binding = CanonicalOperationWireBindings.changeApply
        assertEquals("kast.change.apply.v3", binding.schema.value)
        val cases =
            ChangeApplyUnverifiedReason.entries.map {
                ChangeApplyResult.AppliedUnverified(text("plan:1"), preview(), it)
            } +
                ChangeApplyRecoveryReason.entries.map {
                    ChangeApplyResult.RecoveryRequired(text("plan:1"), preview(), it)
                }
        for (result in cases) {
            val qualifier =
                when (result) {
                    is ChangeApplyResult.AppliedUnverified -> ChangeApplyQualification.APPLIED_UNVERIFIED
                    is ChangeApplyResult.RecoveryRequired -> ChangeApplyQualification.RECOVERY_REQUIRED
                    is ChangeApplyResult.Verified -> error("unexpected verified fixture")
                }
            val document =
                binding
                    .encodeOutcome(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(binding.operation.id, EvidenceGeneration.parse(17).refinedValue(), result),
                            qualifier,
                        )
                    )
                    .encodedDocument()
            val decoded = binding.decodeOutcome(document) as WireDecoding.Decoded
            assertEquals(result, (decoded.value as OperationOutcome.Qualified).evidence.payload)
            val payload = document.qualifiedBody().result.jsonObject
            assertEquals("plan:1", payload.getValue("planIdentity").jsonPrimitive.content)
            assertTrue("receiptIdentity" !in payload)
            val counterfeit =
                document.replace(
                    "\"planIdentity\":\"plan:1\"",
                    "\"planIdentity\":\"plan:1\",\"receiptIdentity\":\"fake\"",
                )
            assertTrue(binding.decodeOutcome(counterfeit) is WireDecoding.Rejected)
            assertTrue(
                binding.decodeOutcome(document.replace("kast.change.apply.v3", "kast.change.apply.v2"))
                    is WireDecoding.Rejected
            )
        }
    }

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
    > OperationWireBinding<Request, Result, Qualification, Rejection>.requestPayload(request: Request): JsonElement =
        encodeRequest(request).encodedDocument().requestBody().value

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
    > OperationWireBinding<Request, Result, Qualification, Rejection>.resultPayload(result: Result): JsonElement =
        encodeOutcome(
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        operation.id,
                        EvidenceGeneration.parse(17).refinedValue(),
                        result,
                    )
                )
            )
            .encodedDocument()
            .completeBody()
            .result

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
    > OperationWireBinding<Request, Result, Qualification, Rejection>.qualificationPayload(
        result: Result,
        qualification: Qualification,
    ): JsonElement =
        encodeOutcome(
                OperationOutcome.Qualified(
                    EvidenceEnvelope(
                        operation.id,
                        EvidenceGeneration.parse(17).refinedValue(),
                        result,
                    ),
                    qualification,
                )
            )
            .encodedDocument()
            .qualifiedBody()
            .qualification

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
    > OperationWireBinding<Request, Result, Qualification, Rejection>.rejectionPayload(
        rejection: Rejection
    ): JsonElement = encodeOutcome(OperationOutcome.Rejected(rejection)).encodedDocument().rejectedBody().rejection

    private fun String.requestBody(): WireBodyDocument.Request = (admittedEnvelope().body as WireBodyDocument.Request)

    private fun String.completeBody(): WireBodyDocument.Complete =
        (admittedEnvelope().body as WireBodyDocument.Complete)

    private fun String.qualifiedBody(): WireBodyDocument.Qualified =
        (admittedEnvelope().body as WireBodyDocument.Qualified)

    private fun String.rejectedBody(): WireBodyDocument.Rejected =
        (admittedEnvelope().body as WireBodyDocument.Rejected)

    private fun String.admittedEnvelope(): AdmittedWireEnvelope =
        when (val admission = admitWireEnvelope(this)) {
            is WireEnvelopeAdmission.Admitted -> admission.envelope
            is WireEnvelopeAdmission.Rejected -> error("Expected envelope, got ${admission.failure}")
        }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

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
            is WireEncoding.Rejected -> error("Expected encoding, got $failure")
        }

    private fun WireRequestAdmission.admittedRequest(): AdmittedWireRequest =
        when (this) {
            is WireRequestAdmission.Admitted -> request
            is WireRequestAdmission.Rejected -> error("Expected request, got $failure")
        }
}
