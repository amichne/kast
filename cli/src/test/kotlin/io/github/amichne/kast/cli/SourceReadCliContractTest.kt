package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceLineCountDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireRequestAdmission
import io.github.amichne.kast.protocol.wire.WireRequestEnvelope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class SourceReadCliContractTest {
    @Test
    fun `minimal source read resolves every canonical default before wire encoding`() {
        val token = selectorToken("exact", "v2")
        val expected = SourceReadRequest(
                SourceReadAnchorDocument.Symbol(protocolText(token)),
                SourceRegionSelectionDocument.Anchor,
                SourceEntitySelectionDocument.None,
                SourceTextRequestDocument.Complete,
                SourceEntityLimitDocument.parse(250).refinedValue(),
                SourceTextByteLimitDocument.parse(65_536).refinedValue(),
                SourceReadPageDocument.First,
        )

        assertEquals(expected, sourceRequest(expected))
    }

    @Test
    fun `source read composes structural filters window bounds and continuation`() {
        val token = selectorToken("source-selector-v1", null)
        val expected = SourceReadRequest(
                SourceReadAnchorDocument.Source(protocolText(token)),
                SourceRegionSelectionDocument.Enclosing(
                    SourceEnclosingRegionKindDocument.CALLABLE_BODY,
                ),
                SourceEntitySelectionDocument.Matching(
                    SourceContainmentDocument.DESCENDANTS,
                    listOf(
                        SourceEntityFilterDocument.Declarations(
                            listOf(
                                SourceDeclarationKindDocument.FUNCTION,
                                SourceDeclarationKindDocument.PROPERTY,
                            ),
                            SourceVisibilitySelectionDocument.Exact(
                                listOf(
                                    SourceDeclarationVisibilityDocument.PUBLIC,
                                    SourceDeclarationVisibilityDocument.PRIVATE,
                                ),
                            ),
                        ),
                        SourceEntityFilterDocument.Parameters,
                        SourceEntityFilterDocument.Calls,
                        SourceEntityFilterDocument.References,
                    ),
                ),
                SourceTextRequestDocument.Window(
                    SourceLineCountDocument.parse(3).refinedValue(),
                    SourceLineCountDocument.parse(5).refinedValue(),
                ),
                SourceEntityLimitDocument.parse(10).refinedValue(),
                SourceTextByteLimitDocument.parse(4_096).refinedValue(),
                SourceReadPageDocument.Continue(
                    protocolText("source-read-continuation-v1|${"a".repeat(64)}"),
                ),
        )

        assertEquals(expected, sourceRequest(expected))
    }

    @Test
    fun `source read invalid combinations fail at usage refinement`() {
        val token = selectorToken("candidate", "v2")
        val invalid = listOf(
            """{"anchor":{"type":"candidate","selector":"$token"},"region":{"type":"anchor"},"entities":{"type":"matching","containment":"direct","filters":[]},"text":{"type":"complete"},"entityLimit":10,"textByteLimit":4096,"page":{"type":"first"}}""",
            """{"anchor":{"type":"candidate","selector":"$token"},"region":{"type":"anchor"},"entities":{"type":"none"},"text":{"type":"window","beforeLines":-1,"afterLines":0},"entityLimit":10,"textByteLimit":4096,"page":{"type":"first"}}""",
            """{"anchor":{"type":"candidate","selector":"$token"},"region":{"type":"anchor"},"entities":{"type":"matching","containment":"direct","filters":[{"type":"declaration","kinds":["function","function"],"visibility":{"type":"any"}}]},"text":{"type":"complete"},"entityLimit":10,"textByteLimit":4096,"page":{"type":"first"}}""",
        )

        invalid.forEach { document ->
            assertTrue(
                commandGraphFactory().parse(
                    listOf("source", "read"),
                    CliRequestDocumentInput.Provided(document),
                ) is CliCommandParsing.Rejected,
            )
        }
        assertTrue(
            commandGraphFactory().parse(
                listOf("source", "read"),
                CliRequestDocumentInput.Provided(
                    """{"anchor":{"type":"candidate","selector":"unknown:selector"},"region":{"type":"anchor"},"entities":{"type":"none"},"text":{"type":"complete"},"entityLimit":10,"textByteLimit":4096,"page":{"type":"first"}}""",
                ),
            ) is CliCommandParsing.Rejected,
        )
    }

    private fun sourceRequest(request: SourceReadRequest): SourceReadRequest {
        val document = Json { encodeDefaults = true; classDiscriminator = "type" }
            .encodeToString(SourceReadRequest.serializer(), request)
        return commandGraphFactory().parse(
            listOf("source", "read"),
            CliRequestDocumentInput.Provided(document),
        ).sourceRequest()
    }

    private fun CliCommandParsing.sourceRequest(): SourceReadRequest {
        val parsed = this as? CliCommandParsing.Parsed ?: error("Expected parsed command: $this")
        val semantic = parsed.action as? CliAction.Semantic ?: error("Expected semantic action")
        val admitted = when (val envelope = WireRequestEnvelope.admit(semantic.request.document)) {
            is WireRequestAdmission.Admitted -> envelope.request
            is WireRequestAdmission.Rejected -> error("Expected admitted request: ${envelope.failure}")
        }
        return when (val decoded = CanonicalOperationWireBindings.sourceRead.decodeRequest(admitted)) {
            is WireDecoding.Decoded -> decoded.value
            is WireDecoding.Rejected -> error("Expected decoded request: ${decoded.failure}")
        }
    }

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error("command graph: ${construction.failures}")
    }

    private fun selectorToken(prefix: String, version: String?): String {
        val payload = "{}".toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return listOfNotNull(prefix, version, encoded, digest).joinToString(":")
    }

    private fun protocolText(raw: String) =
        io.github.amichne.kast.protocol.contract.ProtocolText.parse(raw).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
