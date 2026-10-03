package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.hosted.HostedPeerSiteFixture
import io.github.amichne.kast.runtime.hosted.peerValue
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegisteredPeerReadTest {
    @Test
    fun `unregistered exact host never discovers a project or performs a read`() = runTest {
        val f = HostedPeerSiteFixture()
        val result =
            readRegisteredPeer<Unit>(
                emptyMap(),
                f.basis,
                canonicalRoot = { error("Missing registry entry cannot inspect a root") },
                action = { _, _ -> error("Missing registry entry cannot invoke a peer") },
            )
        assertEquals(
            HostedSemanticReadResult.Rejected(
                HostedQueryFailure.PROJECT_UNAVAILABLE,
                HostedQueryStage.REQUEST_ADMISSION,
            ),
            result,
        )
    }

    @Test
    fun `registered project with another actual canonical root cannot authorize the claim`() = runTest {
        val f = HostedPeerSiteFixture()
        val project =
            Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
                when (method.name) {
                    "isDisposed" -> false
                    "getBasePath" -> "/registered"
                    else -> error("Unexpected native access before exact root admission: ${method.name}")
                }
            } as Project
        var roots = 0
        val result =
            readRegisteredPeer<Unit>(
                mapOf(f.authority.reference.host.value to project),
                f.basis,
                canonicalRoot = { path ->
                    assertEquals(0, roots++)
                    assertEquals("/registered", path)
                    Refinement.Refined(CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/different")).peerValue())
                },
                action = { _, _ -> error("Wrong canonical root cannot invoke a peer") },
            )
        assertEquals(
            HostedSemanticReadResult.Rejected(
                HostedQueryFailure.WRONG_PROJECT,
                HostedQueryStage.REQUEST_ADMISSION,
            ),
            result,
        )
        assertEquals(1, roots)
    }
}
