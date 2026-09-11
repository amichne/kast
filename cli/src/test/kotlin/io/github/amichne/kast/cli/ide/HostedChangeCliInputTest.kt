package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.*
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedChangeCliInputTest {
    private val identity = "plan:${"a".repeat(64)}"
    private val assertion =
        Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray()) +
            "." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))

    @Test
    fun `approved apply and recovery retain exact request identity and opaque assertion`() {
        for (verb in listOf("apply", "recover")) {
            val root = CanonicalRoot(Path.of("/workspace"))
            var calls = 0
            executeExistingIdeCli(
                listOf("change", verb, "--stdin", "--hosted-approved-invocation"),
                root.path,
                CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                ExistingIdeClient { _, operation ->
                    calls++
                    val approved = operation as ExistingIdeOperation.ApprovedMutation
                    assertEquals(identity, approved.identity.value)
                    assertEquals(assertion, approved.assertion.value)
                    assertEquals(
                        identity,
                        Json.parseToJsonElement(approved.request.document)
                            .jsonObject["body"]!!
                            .jsonObject["value"]!!
                            .jsonObject["planIdentity"]!!
                            .jsonPrimitive
                            .content,
                    )
                    ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
                },
                CliRequestDocumentInput.Provided(
                    """{"arguments":{"planIdentity":"$identity"},"approval":"$assertion"}"""
                ),
            )
            assertEquals(1, calls)
        }
    }

    @Test
    fun `private flags and malformed envelopes fail before host effects`() {
        val malformed =
            listOf(
                """{"arguments":{"planIdentity":"$identity"},"approval":"$assertion","extra":1}""",
                """{"arguments":{"planIdentity":"$identity","planIdentity":"$identity"},"approval":"$assertion"}""",
                """{"arguments":{"planIdentity":"$identity"},"approval":"$assertion"} {}""",
                """{"arguments":{"planIdentity":"$identity"},"approval":"true"}""",
                """{"arguments":{"planIdentity":"invalid"},"approval":"$assertion"}""",
            )
        for (document in malformed) assertTrue(
            admitHostedCliInput(
                listOf("change", "apply", "--hosted-approved-invocation"),
                CliRequestDocumentInput.Provided(document),
            )
                is Refinement.Rejected
        )
        for (args in
            listOf(
                listOf("change", "plan", "--hosted-approval-prepare"),
                listOf("change", "apply", "--hosted-approved-invocation", "--hosted-approval-prepare"),
                listOf("ide", "status", "--hosted-approval-prepare"),
            )) {
            assertTrue(
                admitHostedCliInput(args, CliRequestDocumentInput.Deferred { error("must not read") })
                    is Refinement.Rejected
            )
        }
    }

    @Test
    fun `help does not consume stdin`() {
        assertTrue(
            admitHostedCliInput(
                listOf("change", "apply", "--help"),
                CliRequestDocumentInput.Deferred { error("must not read") },
            )
                is Refinement.Refined
        )
    }

    @Test
    fun `plan duplicate properties are rejected before canonical projection`() {
        assertTrue(
            admitHostedCliInput(
                listOf("change", "plan"),
                CliRequestDocumentInput.Provided("""{"intent":{},"intent":{}}"""),
            )
                is Refinement.Rejected
        )
    }
}
