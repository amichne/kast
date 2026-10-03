package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.EvidenceGenerationFailure
import io.github.amichne.kast.kernel.PermanentIdentityFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.contract.SchemaIdentityFailure
import io.github.amichne.kast.protocol.wire.WireBodyKind
import io.github.amichne.kast.protocol.wire.WireFailure
import io.github.amichne.kast.protocol.wire.WireValueRole
import io.github.amichne.kast.protocol.wire.presentation.OperationProjectionFailure
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExistingIdeResponseShapeTest {
    @Test
    fun `private stage records encode independent closed shapes`() {
        val encoded =
            responseEvidenceJson.encodeToString(
                ListSerializer(ExistingIdeResponseActivity.serializer()),
                activityCases(),
            )
        assertEquals(expected("evidence.json"), responseEvidenceJson.parseToJsonElement(encoded))
    }

    @Test
    fun `all canonical wire failure variants retain finite causes without unknown identity text`() {
        val encoded =
            responseEvidenceJson.encodeToString(
                ListSerializer(ExistingIdeResponseCause.serializer()),
                wireCases().map { it.responseCause() },
            )
        assertEquals(expected("wire-causes.json"), responseEvidenceJson.parseToJsonElement(encoded))
    }

    @Test
    fun `unknown evidence discriminator cannot acquire a stage outcome`() {
        assertThrows(SerializationException::class.java) {
            responseEvidenceJson.decodeFromString(
                ExistingIdeResponseEvidence.serializer(),
                checkNotNull(javaClass.getResource("/host-response/unknown-evidence.json")).readText(),
            )
        }
    }

    @Test
    fun `effect observer emits only the independent bounded activity document`() {
        val output = ByteArrayOutputStream()
        val descriptor =
            ExistingIdeDescriptor(
                (HostedEndpointOwnerPid.parse("123") as Refinement.Refined).value,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
            )
        PrintStream(output).use { stream ->
            JsonLineExistingIdeResponseObserver(descriptor, ExistingIdeOperation.Status, stream)
                .record(ExistingIdeResponseEvidence.Frame(80342, 65536, ExistingIdeResponseFrameOutcome.OVER_LIMIT))
        }
        assertEquals(
            expected("emitted-over-limit.json"),
            responseEvidenceJson.parseToJsonElement(output.toString(Charsets.UTF_8)),
        )
    }

    private fun activityCases(): List<ExistingIdeResponseActivity> =
        (framingCases() + decodedCases() + rejectionCases()).map {
            ExistingIdeResponseActivity(123, ExistingIdeResponseOperation.QUERY_RUN, it)
        }

    private fun framingCases(): List<ExistingIdeResponseEvidence> =
        listOf(
            ExistingIdeResponseEvidence.Frame(3, 65536, ExistingIdeResponseFrameOutcome.ADMITTED),
            ExistingIdeResponseEvidence.Frame(0, 65536, ExistingIdeResponseFrameOutcome.NONPOSITIVE),
            ExistingIdeResponseEvidence.Frame(80342, 65536, ExistingIdeResponseFrameOutcome.OVER_LIMIT),
            ExistingIdeResponseEvidence.Body(3, 3, ExistingIdeResponseBodyOutcome.DRAINED),
            ExistingIdeResponseEvidence.Body(3, 1, ExistingIdeResponseBodyOutcome.TRUNCATED),
        )

    private fun decodedCases(): List<ExistingIdeResponseEvidence> =
        listOf(
            ExistingIdeResponseEvidence.Decoded(ExistingIdeResponseDecodeOutcome.RECEIVED),
            ExistingIdeResponseEvidence.Decoded(ExistingIdeResponseDecodeOutcome.SEMANTIC),
            ExistingIdeResponseEvidence.Decoded(ExistingIdeResponseDecodeOutcome.HOST_REJECTED),
        )

    private fun rejectionCases(): List<ExistingIdeResponseEvidence> =
        listOf(
            ExistingIdeDecodedResponse.rejected(ExistingIdeResponseDecodeFailure.StrictJson).evidence,
            ExistingIdeDecodedResponse.rejected(ExistingIdeResponseDecodeFailure.Wire(WireFailure.MalformedEnvelope))
                .evidence,
        ) +
            basisCases() +
            completionCases() +
            listOf(
                ExistingIdeDecodedResponse.fromExchange(
                        ExistingIdeExchange.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
                    )
                    .evidence
            )

    private fun basisCases(): List<ExistingIdeResponseEvidence> =
        listOf(
                ExistingIdeResponseBasisFailure.PUBLISHED,
                ExistingIdeResponseBasisFailure.ROOT_MISMATCH,
                ExistingIdeResponseBasisFailure.HOST_MISMATCH,
            )
            .map { ExistingIdeDecodedResponse.rejected(ExistingIdeResponseDecodeFailure.Basis(it)).evidence }

    private fun completionCases(): List<ExistingIdeResponseEvidence> =
        listOf(
            ExistingIdeDecodedResponse.rejected(
                    ExistingIdeResponseDecodeFailure.Completion(
                        OperationProjectionFailure.RequestEncodingFailed(
                            CanonicalOperation.QUERY_RUN,
                            WireFailure.InvalidPayload(WireValueRole.REQUEST),
                        )
                    )
                )
                .evidence,
            ExistingIdeDecodedResponse.rejected(
                    ExistingIdeResponseDecodeFailure.Completion(
                        OperationProjectionFailure.ResponseDecodingFailed(
                            CanonicalOperation.DIAGNOSTIC_CHECK,
                            WireFailure.InvalidImpactAccounting(ImpactAccountingFailure.MISSING_VALUE_ACCOUNTING),
                        )
                    )
                )
                .evidence,
        )

    private fun wireCases(): List<WireFailure> =
        listOf(
            WireFailure.MalformedEnvelope,
            WireFailure.InvalidImpactAccounting(ImpactAccountingFailure.COMPLETION_NOT_CONSERVED),
            WireFailure.InvalidSchemaIdentity(SchemaIdentityFailure.BLANK),
            WireFailure.UnknownSchema((SchemaIdentity.parse("unreviewed.identity") as Refinement.Refined).value),
            WireFailure.InvalidOperationIdentity(PermanentIdentityFailure.INVALID_FORMAT),
            WireFailure.UnknownOperation(CanonicalOperation.QUERY_RUN.id),
            WireFailure.UnexpectedOperation(CanonicalOperation.QUERY_RUN, CanonicalOperation.SOURCE_READ),
            WireFailure.UnexpectedBody(setOf(WireBodyKind.QUALIFIED), WireBodyKind.COMPLETE),
            WireFailure.InvalidEvidenceGeneration(EvidenceGenerationFailure.NEGATIVE),
            WireFailure.InvalidPayload(WireValueRole.RESULT),
            WireFailure.PayloadEncodingFailed(WireValueRole.REJECTION),
        )

    private fun expected(name: String) =
        responseEvidenceJson.parseToJsonElement(checkNotNull(javaClass.getResource("/host-response/$name")).readText())
}
