package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ExistingIdeResponseEvidenceTest {
    @Test
    fun `unrepresentable default frame rejects before reading its body`() {
        val input = framed(Int.MAX_VALUE, byteArrayOf(1))
        val evidence = ArrayList<ExistingIdeResponseEvidence>()
        val exchange =
            readExistingIdeResponse(input, ReadLimits.Default, evidence::add) {
                error("Unrepresentable response reached decoder")
            }
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), exchange)
        assertEquals(1, input.available())
        assertEquals(
            listOf(
                ExistingIdeResponseEvidence.Frame(
                    Int.MAX_VALUE,
                    2_147_483_646,
                    ExistingIdeResponseFrameOutcome.OVER_LIMIT,
                )
            ),
            evidence,
        )
    }

    @Test
    fun `default framing drains and validates a response larger than the historical cap`() {
        val fixture = ResponseFixture()
        val bytes = fixture.reply() + ByteArray(1_572_864) { 32 }
        val evidence = ArrayList<ExistingIdeResponseEvidence>()
        val exchange =
            readExistingIdeResponse(framed(bytes.size, bytes), ReadLimits.Default, evidence::add) { raw ->
                ExistingIdeDocuments.responseWithEvidence(
                    raw = raw,
                    root = fixture.root,
                    operation = fixture.operation,
                    descriptor = fixture.descriptor,
                )
            }
        assertInstanceOf(ExistingIdeExchange.Semantic::class.java, exchange)
        assertEquals(ExistingIdeResponseBodyOutcome.DRAINED, (evidence[1] as ExistingIdeResponseEvidence.Body).outcome)
        assertEquals(ExistingIdeResponseEvidence.Decoded(ExistingIdeResponseDecodeOutcome.SEMANTIC), evidence.last())
    }

    @Test
    fun `valid semantic response records exact frame body and accepted decoder outcome`() {
        val fixture = ResponseFixture()
        val bytes = fixture.reply()
        val evidence = ArrayList<ExistingIdeResponseEvidence>()
        val exchange =
            readExistingIdeResponse(framed(bytes.size, bytes), hostLimits, evidence::add) { raw ->
                ExistingIdeDocuments.responseWithEvidence(
                    raw = raw,
                    root = fixture.root,
                    operation = fixture.operation,
                    descriptor = fixture.descriptor,
                )
            }
        assertInstanceOf(ExistingIdeExchange.Semantic::class.java, exchange)
        assertEquals(
            listOf(
                ExistingIdeResponseEvidence.Frame(bytes.size, 65536, ExistingIdeResponseFrameOutcome.ADMITTED),
                ExistingIdeResponseEvidence.Body(bytes.size, bytes.size, ExistingIdeResponseBodyOutcome.DRAINED),
                ExistingIdeResponseEvidence.Decoded(ExistingIdeResponseDecodeOutcome.SEMANTIC),
            ),
            evidence,
        )
    }

    @Test
    fun `oversize frame records rejection before reading body or invoking decoder`() {
        val evidence = ArrayList<ExistingIdeResponseEvidence>()
        var decodes = 0
        val input = framed(65537, byteArrayOf(1, 2, 3))
        val exchange =
            readExistingIdeResponse(input, hostLimits, evidence::add) {
                decodes++
                error("Oversize response reached decoder")
            }
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), exchange)
        assertEquals(0, decodes)
        assertEquals(3, input.available())
        assertEquals(
            listOf(ExistingIdeResponseEvidence.Frame(65537, 65536, ExistingIdeResponseFrameOutcome.OVER_LIMIT)),
            evidence,
        )
    }

    @Test
    fun `truncated body records exact drained bytes and cannot reach decoder`() {
        val evidence = ArrayList<ExistingIdeResponseEvidence>()
        var decodes = 0
        val exchange =
            readExistingIdeResponse(framed(3, byteArrayOf(1)), hostLimits, evidence::add) {
                decodes++
                error("Truncated response reached decoder")
            }
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), exchange)
        assertEquals(0, decodes)
        assertEquals(
            listOf(
                ExistingIdeResponseEvidence.Frame(3, 65536, ExistingIdeResponseFrameOutcome.ADMITTED),
                ExistingIdeResponseEvidence.Body(3, 1, ExistingIdeResponseBodyOutcome.TRUNCATED),
            ),
            evidence,
        )
    }

    @Test
    fun `malformed JSON and malformed canonical envelope retain different causes`() {
        val fixture = ResponseFixture()
        val invalidJson = fixture.decode("{")
        val invalidEnvelope =
            fixture.decode(checkNotNull(javaClass.getResource("/host-response/invalid-envelope.json")).readText())
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), invalidJson.exchange)
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), invalidEnvelope.exchange)
        assertEquals(
            ExistingIdeResponseEvidence.Rejected(
                ExistingIdeResponseStage.STRICT_JSON,
                ExistingIdeFailure.RESPONSE_REJECTED,
                ExistingIdeResponseCause.StrictJson,
            ),
            invalidJson.evidence,
        )
        assertEquals(
            ExistingIdeResponseEvidence.Rejected(
                ExistingIdeResponseStage.CANONICAL_WIRE,
                ExistingIdeFailure.RESPONSE_REJECTED,
                ExistingIdeResponseCause.MalformedEnvelope,
            ),
            invalidEnvelope.evidence,
        )
    }

    @Test
    fun `foreign live host remains a finite basis rejection after canonical decoding`() {
        val fixture = ResponseFixture()
        val rejected =
            fixture.decode(fixture.reply(UUID.fromString("00000000-0000-0000-0000-000000000002")).decodeToString())
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), rejected.exchange)
        assertEquals(
            ExistingIdeResponseEvidence.Rejected(
                ExistingIdeResponseStage.LIVE_BASIS,
                ExistingIdeFailure.RESPONSE_REJECTED,
                ExistingIdeResponseCause.Basis(ExistingIdeResponseBasisFailure.HOST_MISMATCH),
            ),
            rejected.evidence,
        )
    }

    @Test
    fun `nonpositive frame remains finite and does not invoke decoder`() {
        val evidence = ArrayList<ExistingIdeResponseEvidence>()
        val exchange =
            readExistingIdeResponse(framed(0, byteArrayOf()), hostLimits, evidence::add) {
                error("Nonpositive response reached decoder")
            }
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), exchange)
        assertEquals(
            listOf(ExistingIdeResponseEvidence.Frame(0, 65536, ExistingIdeResponseFrameOutcome.NONPOSITIVE)),
            evidence,
        )
    }

    @Test
    fun `published canonical evidence retains the precise private basis cause`() {
        val fixture = ResponseFixture()
        val published = EvidenceBasis.Published((EvidenceGeneration.parse(7) as Refinement.Refined).value)
        val rejected = fixture.decode(fixture.replyWithBasis(published).decodeToString())
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), rejected.exchange)
        assertEquals(
            ExistingIdeResponseEvidence.Rejected(
                ExistingIdeResponseStage.LIVE_BASIS,
                ExistingIdeFailure.RESPONSE_REJECTED,
                ExistingIdeResponseCause.Basis(ExistingIdeResponseBasisFailure.PUBLISHED),
            ),
            rejected.evidence,
        )
    }

    @Test
    fun `foreign canonical root retains the precise private basis cause`() {
        val fixture = ResponseFixture()
        val rejected = fixture.decode(fixture.reply(path = "/other").decodeToString())
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), rejected.exchange)
        assertEquals(
            ExistingIdeResponseEvidence.Rejected(
                ExistingIdeResponseStage.LIVE_BASIS,
                ExistingIdeFailure.RESPONSE_REJECTED,
                ExistingIdeResponseCause.Basis(ExistingIdeResponseBasisFailure.ROOT_MISMATCH),
            ),
            rejected.evidence,
        )
    }

    private fun framed(announced: Int, bytes: ByteArray): DataInputStream {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(announced)
            stream.write(bytes)
        }
        return DataInputStream(ByteArrayInputStream(output.toByteArray()))
    }

    private val hostLimits =
        (ReadLimits.resolve(mapOf("KAST_READ_HOST_RESPONSE_BYTES" to "65536")) as Refinement.Refined).value
}

private class ResponseFixture {
    val root = CanonicalRoot(Path.of("/workspace"))
    val descriptor =
        ExistingIdeDescriptor(
            refined(HostedEndpointOwnerPid.parse("123")),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )
    val operation: ExistingIdeOperation.Read =
        refined(
            ExistingIdeOperation.Read.admit(
                (canonicalCliRequestPreparers()
                        .diagnosticCheck
                        .prepare(
                            DiagnosticCheckRequest(
                                refined(ProtocolText.parse("src")),
                                refined(ProtocolCount.parse(10)),
                            )
                        ) as OperationPreparation.Prepared)
                    .request
            )
        )

    fun reply(host: UUID = descriptor.host, path: String = root.path.toString()): ByteArray {
        val basis =
            EvidenceBasis.Live(
                refined(
                    LiveReadEvidence.create(
                        workspaceRoot = path,
                        host = host,
                        epoch = 7,
                        contentView = LiveReadContentView.SAVED_PSI_COMMITTED,
                        version = 1,
                    )
                )
            )
        return replyWithBasis(basis)
    }

    fun replyWithBasis(basis: EvidenceBasis): ByteArray {
        val binding = CanonicalOperationWireBindings.diagnosticCheck
        val result = DiagnosticCheckResult(refined(BoundedProtocolList.create(emptyList())))
        return (binding.encodeOutcome(OperationOutcome.Complete(EvidenceEnvelope(binding.operation.id, basis, result)))
                as WireEncoding.Encoded)
            .document
            .toByteArray()
    }

    fun decode(raw: String): ExistingIdeDecodedResponse =
        ExistingIdeDocuments.responseWithEvidence(
            raw = raw.toByteArray(),
            root = root,
            operation = operation,
            descriptor = descriptor,
        )

    private fun <Value, Failure> refined(value: Refinement<Value, Failure>): Value = (value as Refinement.Refined).value
}
