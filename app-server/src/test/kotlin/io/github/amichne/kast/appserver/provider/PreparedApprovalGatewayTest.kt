package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.runtime.HostedChangeApprovalOperation
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalRejection
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalRequest
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationId
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreparedApprovalGatewayTest {
    @Test
    fun `approval challenge uses workspace demand exactly once without falling back`(@TempDir home: Path) = runTest {
        enroll(home)
        val root = CanonicalRoot(home.toRealPath())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val operations = mutableListOf<ExistingIdeOperation>()
        val options =
            KastProviderOptions(
                catalogSource = KastCatalogSource { error("No catalog read") },
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                ideClient = ExistingIdeClient { _, _ -> error("No direct fallback") },
                workspaceDemand =
                    WorkspaceDemand { selected, operation ->
                        assertEquals(root, selected)
                        operations += operation
                        WorkspaceDemandResult.Native(
                            ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)
                        )
                    },
                ioDispatcher = dispatcher,
            )
        val request = request(home)
        assertEquals(
            Refinement.Rejected(HostedPlanApprovalFailure.PLAN_UNAVAILABLE),
            KastHostedPlanApprovalGateway(options, home, dispatcher).prepare(request),
        )
        val prepared = operations.single() as ExistingIdeOperation.ApprovalPreparation
        assertEquals(HostedMutationOperation.CHANGE_APPLY, prepared.kind)
        assertEquals("plan:${request.planIdentity}", prepared.identity.value)
    }

    @Test
    fun `workspace blocker retains its operation proof before challenge creation`(@TempDir home: Path) = runTest {
        enroll(home)
        val root = CanonicalRoot(home.toRealPath())
        val failure =
            WorkspaceDemandFailure.Operation(
                WorkspacePreparationId.fresh(),
                root,
                WorkspaceDemandCause.Lifecycle(
                    io.github.amichne.kast.protocol.contract.IdeLifecycleFailure.TRUST_REQUIRED
                ),
            )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val options =
            KastProviderOptions(
                catalogSource = KastCatalogSource { error("No catalog read") },
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                ideClient = ExistingIdeClient { _, _ -> error("No direct fallback") },
                workspaceDemand = WorkspaceDemand { _, _ -> WorkspaceDemandResult.Rejected(failure) },
                ioDispatcher = dispatcher,
            )
        assertEquals(
            Refinement.Rejected(HostedPlanApprovalRejection.Workspace(failure)),
            KastHostedPlanApprovalGateway(options, home, dispatcher).prepare(request(home)),
        )
    }

    private fun request(home: Path): HostedPlanApprovalRequest {
        val invocation =
            (BrokerInvocationContext.admit("thread", "turn", "call", home.toRealPath()) as Refinement.Refined).value
        return (HostedPlanApprovalRequest.admit(
                HostedChangeApprovalOperation.APPLY,
                invocation,
                Json.encodeToJsonElement(Arguments("plan:${"a".repeat(64)}")),
            ) as Refinement.Refined)
            .value
    }

    private fun enroll(home: Path) {
        val directory = Files.createDirectories(home.resolve(".kast/approval"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        listOf("broker.pk8" to pair.private.encoded, "broker.pub" to pair.public.encoded).forEach { (name, bytes) ->
            Files.write(directory.resolve(name), bytes)
            Files.setPosixFilePermissions(directory.resolve(name), PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Serializable private data class Arguments(val planIdentity: String)
}
