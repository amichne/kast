package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.direct.DirectToolDocument
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.cli.rpc.KastToolRpcBridge
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicAuthorityReadTest {
    @Test
    fun `source follow-up cannot start an eager lifecycle operation in the public CLI or RPC`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle.kts"), "")
        val command =
            (parsePublicCliArguments(listOf("source", "read", "--ref", "exact:v5:AAAAAAAAAAAAAAAAAAAAAA"))
                    as Refinement.Refined)
                .value as PublicCliCommand.Query
        val identity = PublicToolIdentity.QUERY_SYMBOLS
        val admitted =
            (PublicToolContract.admit(identity, (command.input as PublicCliInput.Lowered).arguments)
                    as Refinement.Refined)
                .value
        var invocations = 0
        val session =
            KastDirectToolSession(
                catalog =
                    listOf(
                        DirectToolDocument(
                            identity.toolName,
                            "Query",
                            PublicToolContract.parameters(identity),
                            McpStructuredResults.schemaFor(identity.toolName),
                            OperationEffect.INTELLIJ_READ,
                        )
                    ),
                root = { FilesystemCanonicalRootDiscovery.discover(root) },
                invokePublic = { request ->
                    assertSame(admitted, request)
                    invocations++
                    boundaryExit(CliBoundaryExitStatus.TRANSPORT, "independent-native-rejection")
                },
            )
        assertEquals(PublicShellExit.IO, invokePublicSession(session, admitted).exit)
        KastToolRpcBridge(session).callAdmitted(admitted)
        assertEquals(2, invocations)
    }
}
