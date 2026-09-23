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
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.canonicalRootFixture
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExistingIdeChangeCliTest {
    private fun text(value: String) = (ProtocolText.parse(value) as Refinement.Refined).value

    @Test
    fun `supported plan reaches only the daemon capability`() {
        val root = canonicalRootFixture(Path.of("/workspace"))
        var calls = 0
        val result =
            executeExistingIdeCli(
                argv = listOf("change", "plan"),
                start = root.path,
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                client =
                    ExistingIdeClient { _, _ ->
                        error("Plan reached direct IDE")
                    },
                requestInput =
                    CliRequestDocumentInput.Provided(
                        Json.encodeToString(
                            ChangePlanRequest(
                                ChangeIntentDocument.AddDeclaration(
                                    text("exact:opaque_ref"),
                                    text("fun added() = Unit"),
                                )
                            )
                        )
                    ),
                read =
                    DaemonOperationClient { admittedRoot, call ->
                        assertEquals(root, admittedRoot)
                        assertTrue((call as DaemonOperationCall.Change).action is DaemonChangeAction.Plan)
                        calls++
                        DaemonOperationResult.Rejected(
                            DaemonOperationClientRejection.Server(
                                DaemonOperationFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE)
                            )
                        )
                    },
            )
        assertEquals(1, calls)
        assertTrue(result.document.value.contains("daemon-operation-host-host-unavailable"))
    }

    @Test
    fun `approval preparation reaches hosted ingress with exact plan identity`() {
        val root = canonicalRootFixture(Path.of("/workspace"))
        var calls = 0
        executeExistingIdeCli(
            argv = listOf("change", "apply", "--stdin", "--hosted-approval-prepare"),
            start = root.path,
            roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
            client =
                ExistingIdeClient { _, _ ->
                    error("Approval preparation reached direct IDE")
                },
            requestInput = CliRequestDocumentInput.Provided("""{"planIdentity":"plan:${"a".repeat(64)}"}"""),
            read =
                DaemonOperationClient { admittedRoot, call ->
                    assertEquals(root, admittedRoot)
                    val action = (call as DaemonOperationCall.Change).action as DaemonChangeAction.Prepare
                    assertEquals("plan:${"a".repeat(64)}", action.identity)
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

    @Test
    fun `ordinary apply requires approval before calling the hosted capability`() {
        val root = canonicalRootFixture(Path.of("/workspace"))
        var calls = 0
        val result =
            executeExistingIdeCli(
                argv = listOf("change", "apply"),
                start = root.path,
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                client =
                    ExistingIdeClient { _, _ ->
                        calls++
                        ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
                    },
                requestInput = CliRequestDocumentInput.Provided("""{"planIdentity":"plan:${"a".repeat(64)}"}"""),
            )
        assertEquals(0, calls)
        assertTrue(result.document.value.contains("approval-required"))
    }

    @Test
    fun `every change command enters hosted ingress before installed bootstrap`() {
        for (operation in listOf("plan", "apply", "recover", "unsupported")) {
            for (prefix in listOf(emptyList(), listOf("--"))) {
                assertEquals(CliRuntimePath.EXISTING_IDE, selectCliRuntimePath(prefix + listOf("change", operation)))
            }
        }
    }
}
