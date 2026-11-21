package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCapabilityName
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdeInstallationManagementTest {
    @Test
    fun `installer can resume an exact prior Host with a different semantic contract`() {
        val inspected = inspected(Path.of("/owned/idea"))
        val command = IdeLifecycleCommand.ResumeHost(inspected.host, "update")
        val expected = IdeLifecycleResult.HostResumed(inspected.host, "update")
        val exchanges = mutableListOf<Pair<Path, IdeLifecycleCommand>>()
        assertEquals(
            expected,
            manageInspectedInstallation(inspected, command) { home, sent ->
                exchanges += home to sent
                expected
            },
        )
        assertEquals(listOf(Path.of(inspected.home) to command), exchanges)
    }

    @Test
    fun `installer rejects a foreign Host without a second exchange`() {
        val inspected = inspected(Path.of("/owned/idea"))
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.WRONG_HOST),
            manageInspectedInstallation(inspected, IdeLifecycleCommand.ResumeHost("foreign", "update")) { _, _ ->
                error("A foreign Host must not receive a second exchange")
            },
        )
    }

    private fun inspected(idea: Path): IdeLifecycleResult.Inspected {
        val host = UUID(0, 1).toString()
        return IdeLifecycleResult.Inspected(
            host,
            idea.toString(),
            "262.1.1",
            emptyList(),
            HostedCompatibilityDocument(
                "262.1.1",
                "262.1.1-IJ",
                "1.2.3",
                HostedContractDocument(
                    runtimeProtocolIdentity = "different.runtime.v1",
                    operationRegistryDigest = "sha256:" + "a".repeat(64),
                    wireSchemaDigest = "sha256:" + "b".repeat(64),
                    capabilities = listOf("query.run"),
                ),
            ),
            capabilities = setOf(IdeLifecycleCapabilityName.HOST_SETTLEMENT),
            runtimeIdentity =
                IdeHostRuntimeIdentity(
                    idea.toString(),
                    "/profile",
                    42,
                    100,
                    host,
                    IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
                ),
        )
    }
}
