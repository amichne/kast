package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.cli.projection.traversalRunCliProjector
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class CliContinuationAdmissionTest {
    @Test
    fun `emitted traversal continuation above ordinary argv bound resumes through request document`() {
        val token = emittedTraversalContinuation()
        assertTrue(token.length > 4_096)
        assertResumed(traversalArguments(), token)
    }

    @Test
    fun `both continuation families preserve canonical text bound beyond sixty four KiB`() {
        for (payloadSize in listOf(4_200, 70_000, 780_000)) {
            assertResumed(traversalArguments(), continuationEnvelope("traversal", payloadSize))
            assertResumed(
                listOf("relation", "read"),
                continuationEnvelope("relation", payloadSize),
            )
        }
    }

    @Test
    fun `ordinary argv remains bounded independently of request documents`() {
        val token = emittedTraversalContinuation()
        assertEquals(
            CliCommandFailure.ARGUMENT_TOO_LONG,
            rejected(listOf("symbol", "discover", token)).failure,
        )
        assertEquals(
            CliCommandFailure.TOO_MANY_ARGUMENTS,
            rejected(List(67) { "word" }).failure,
        )
    }

    @Test
    fun `long corrupted continuations preserve the finite family rejection diagnostic`() {
        val token = emittedTraversalContinuation()
        for ((command, supplied) in listOf(
            traversalArguments() to "x".repeat(4_097),
            traversalArguments() to (token.dropLast(1) + "z"),
            listOf("relation", "read") to token,
            traversalArguments() to continuationEnvelope("traversal", 800_000),
        )) {
            val rejection = rejected(command, requestDocument(command, supplied))
            assertEquals(CliCommandFailure.ARGUMENTS_REJECTED, rejection.failure)
            assertTrue(rejection.diagnostic.value.contains("canonical, bounded request document"))
        }
    }

    private fun assertResumed(command: List<String>, token: String) {
        val parsed = assertInstanceOf(
            CliCommandParsing.Parsed::class.java,
            factory().parse(
                command,
                CliRequestDocumentInput.Provided(requestDocument(command, token)),
            ),
        )
        val action = assertInstanceOf(CliAction.Semantic::class.java, parsed.action)
        assertTrue(action.request.document.contains(token))
    }

    private fun rejected(arguments: List<String>): CliCommandParsing.Rejected =
        assertInstanceOf(CliCommandParsing.Rejected::class.java, factory().parse(arguments))

    private fun rejected(arguments: List<String>, document: String): CliCommandParsing.Rejected =
        assertInstanceOf(
            CliCommandParsing.Rejected::class.java,
            factory().parse(arguments, CliRequestDocumentInput.Provided(document)),
        )

    private fun requestDocument(command: List<String>, continuation: String): String =
        if (command.first() == "relation") {
            """{"exactSelector":"exact:fixture","relation":"callees","limit":1,"position":{"type":"resume","continuation":"$continuation"}}"""
        } else {
            """{"exactSelector":"exact:fixture","relation":"callees","maximumDepth":3,"maximumResults":1,"position":{"type":"resume","continuation":"$continuation"}}"""
        }

    private fun factory(): CliCommandGraphFactory = when (
        val result = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> result.factory
        is CliCommandGraphConstruction.Rejected -> error("command graph rejected")
    }
}

internal fun traversalArguments(): List<String> = listOf(
    "traversal", "run",
)

/** A public projection emits the complete envelope; no repository source or machine paths enter it. */
internal fun emittedTraversalContinuation(): String {
    val continuation = TraversalContinuationDocument.parse(continuationEnvelope("traversal", 4_200)).refined()
    val projected = traversalRunCliProjector.project(
        OperationOutcome.Qualified(
            EvidenceEnvelope(
                CanonicalOperation.TRAVERSAL_RUN.id,
                EvidenceGeneration.parse(1).refined(),
                TraversalRunResult(
                    ProtocolText.parse("/fixture").refined(),
                    BoundedProtocolList.create(emptyList<io.github.amichne.kast.protocol.contract.TraversalRecordDocument>()).refined(),
                ),
            ),
            TraversalRunQualification.resumable(
                listOf(TraversalLimitationDocument.RECORD_LIMIT_REACHED),
                emptyList(),
                continuation,
            ).refined(),
        ),
    )
    val qualified = assertInstanceOf(ProjectedCliOutcome.Qualified::class.java, projected)
    return Json.parseToJsonElement(qualified.document.value).jsonObject
        .getValue("qualification").jsonObject.getValue("continuation").jsonPrimitive.content
}

private fun continuationEnvelope(family: String, payloadSize: Int): String {
    val payload = "x".repeat(payloadSize).encodeToByteArray()
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    return "$family-continuation:v1:$encoded:$digest"
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("fixture refinement rejected: $failure")
}
