package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class NativeReadIdentityTest : NativeSmartModeFixtureTest() {
    fun testReadAccessRejectionKeepsItsConnectionJoin() = runBlocking {
        val joins = mutableListOf<HostedCorrelatedReadIdentity>()
        val observer =
            object : HostedEndpointObserver {
                override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) = Unit

                override fun readIdentity(observation: HostedCorrelatedReadIdentity) {
                    joins += observation
                }
            }
        val trace = HostedTransportTrace(observer)
        val root =
            (CanonicalWorkspaceRoot.fromCanonicalPath(
                    Files.createDirectories(Path.of(checkNotNull(project.basePath))).toRealPath()
                ) as Refinement.Refined)
                .value
        val owner = SupervisorJob(coroutineContext[Job])
        val service = HostedQueryService(project, CoroutineScope(coroutineContext + owner))
        try {
            val result =
                ReadAction.compute<HostedSemanticReadResult<Int>, RuntimeException> {
                    runBlocking {
                        service.read(service.endpoint, root, observeReadIdentity = trace::readIdentity) {
                            error("Native read access must reject before semantic evaluation")
                        }
                    }
                }
            assertEquals(
                HostedSemanticReadResult.Rejected(HostedQueryFailure.WRONG_THREAD, HostedQueryStage.REQUEST_ADMISSION),
                result,
            )
            val join = joins.single()
            assertEquals(join.readId, UUID.fromString(join.readId).toString())
            assertEquals(join.connectionId, UUID.fromString(join.connectionId).toString())
            assertFalse(join.readId == join.connectionId)
        } finally {
            Disposer.dispose(service)
            owner.cancelAndJoin()
        }
    }
}
