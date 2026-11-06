package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class IdeLifecyclePlatformTest {
    @Test
    fun `workspace lifecycle admits reviewed platforms and preserves exact contract rejection`() {
        val home = Path.of("/disposable/idea")
        for (line in listOf("262", "263", "261", "264")) {
            val build = "$line.7307.200"
            val host =
                IdeLifecycleResult.Inspected(
                    "host",
                    home.toString(),
                    "IU-$build",
                    emptyList(),
                    HostedCompatibilityDocument(build, "$build-IJ", "1.2.3", CanonicalHostedContract.document),
                )
            val actual =
                admitWorkspaceLifecycleHost(
                    home,
                    host,
                    IdeLifecycleCommand.Inspect,
                    SelectedLifecycleDeployment.Desktop,
                )
            if (line in setOf("262", "263")) {
                assertEquals(Refinement.Refined(Unit), actual)
                val changed =
                    host.copy(
                        compatibility =
                            host.compatibility.copy(
                                hostedContract =
                                    host.compatibility.hostedContract.copy(
                                        wireSchemaDigest = "sha256:" + "0".repeat(64)
                                    )
                            )
                    )
                assertEquals(
                    IdeLifecycleFailure.COMPATIBILITY_REJECTED,
                    assertInstanceOf<Refinement.Rejected<IdeLifecycleFailure>>(
                            admitWorkspaceLifecycleHost(
                                home,
                                changed,
                                IdeLifecycleCommand.Inspect,
                                SelectedLifecycleDeployment.Desktop,
                            )
                        )
                        .failure,
                )
            } else {
                assertEquals(Refinement.Rejected(IdeLifecycleFailure.UNSUPPORTED_PLATFORM_LINE), actual)
            }
        }
    }
}
