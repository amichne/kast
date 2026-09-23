package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.DaemonChangeAction
import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.canonicalRootFixture
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
            val root = canonicalRootFixture(Path.of("/workspace"))
            var calls = 0
            executeExistingIdeCli(
                argv = listOf("change", verb, "--stdin", "--hosted-approved-invocation"),
                start = root.path,
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                client =
                    ExistingIdeClient { _, operation ->
                        error("Approved change reached direct IDE: $operation")
                    },
                requestInput =
                    CliRequestDocumentInput.Provided(
                        """{"arguments":{"planIdentity":"$identity"},"approval":"$assertion"}"""
                    ),
                read =
                    DaemonOperationClient { admittedRoot, call ->
                        assertEquals(root, admittedRoot)
                        val action = (call as DaemonOperationCall.Change).action
                        val actualIdentity =
                            when (action) {
                                is DaemonChangeAction.Apply -> {
                                    assertEquals("apply", verb)
                                    assertEquals(assertion, action.assertion)
                                    action.request.planIdentity.value
                                }
                                is DaemonChangeAction.Recover -> {
                                    assertEquals("recover", verb)
                                    assertEquals(assertion, action.assertion)
                                    action.request.planIdentity.value
                                }
                                else -> error("Unexpected change stage: $action")
                            }
                        assertEquals(identity, actualIdentity)
                        calls++
                        DaemonOperationResult.Rejected(
                            DaemonOperationClientRejection.Server(
                                DaemonOperationFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE)
                            )
                        )
                    },
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
