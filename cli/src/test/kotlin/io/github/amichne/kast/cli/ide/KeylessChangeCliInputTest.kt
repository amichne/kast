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
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeylessChangeCliInputTest {
    @Test
    fun `canonical apply and recover reach existing transport with exact plan identity`() {
        val identity = "plan:${"a".repeat(64)}"
        for (verb in listOf("apply", "recover")) {
            val root = canonicalRootFixture(Path.of("/workspace"))
            val calls = mutableListOf<DaemonChangeAction>()
            executeExistingIdeCli(
                argv = listOf("change", verb, "--stdin"),
                start = root.path,
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                client = ExistingIdeClient { _, _ -> error("mutation must use the existing operation client") },
                requestInput =
                    CliRequestDocumentInput.Provided(
                        Json.encodeToString(
                            ChangeApplyRequest.serializer(),
                            ChangeApplyRequest((ProtocolText.parse(identity) as Refinement.Refined).value),
                        )
                    ),
                read =
                    DaemonOperationClient { admittedRoot, call ->
                        assertEquals(root, admittedRoot)
                        val action = (call as DaemonOperationCall.Change).action
                        calls += action
                        val actual =
                            when (action) {
                                is DaemonChangeAction.Apply -> action.request.planIdentity.value
                                is DaemonChangeAction.Recover -> action.request.planIdentity.value
                                else -> error("unexpected change stage: $action")
                            }
                        assertEquals(identity, actual)
                        DaemonOperationResult.Rejected(
                            DaemonOperationClientRejection.Server(
                                DaemonOperationFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE)
                            )
                        )
                    },
            )
            assertEquals(1, calls.size, verb)
        }
    }
}
