package io.github.amichne.kast.ide.endpoint

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectory
import io.github.amichne.kast.protocol.wire.metadata.IdeProcessId
import io.github.amichne.kast.protocol.wire.metadata.IdeRuntimeEpoch
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDescribeReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDiscoverReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolResolveReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.WorkspaceInspectReadPort
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntime
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimeCandidate
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparation
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparationFailure
import io.github.amichne.kast.runtime.ide.host.HostedIdeRuntime as HostedEffectsRuntime
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeEndpointPublicationNegativeTest {
    @Test
    fun `wrong root rejects before bind`() = withDirectory { directory ->
        val result = prepareEndpoint(
            directory,
            projectRoot = endpointRoot("/workspace/kast"),
            descriptorRoot = endpointRoot("/workspace/other"),
        )
        assertPreparationRejected(result, IdeEndpointPublicationFailure.WRONG_ROOT)
        assertDirectoryEmpty(directory)
    }
    @Test
    fun `partial read runtime cannot inhabit endpoint candidate`() {
        assertTrue(
            IdeEndpointService::class.java.constructors.any { constructor ->
                constructor.parameterTypes.contentEquals(
                    arrayOf(Project::class.java, CoroutineScope::class.java),
                )
            },
        )
        assertFalse(IdeEndpointService::class.java.constructors.any { it.parameterCount == 0 })
        assertTrue(
            IdeEndpointPreparationCandidate::class.java.declaredConstructors.any { constructor ->
                constructor.parameterTypes.contains(HostedEffectsRuntime::class.java)
            },
        )
    }
    @Test
    fun `packaged descriptor registers the scoped service and post startup activity`() {
        val descriptor = checkNotNull(javaClass.getResource("/META-INF/plugin.xml")).readText()

        assertTrue(
            descriptor.contains(
                "serviceImplementation=\"io.github.amichne.kast.ide.endpoint.IdeEndpointService\"",
            ),
        )
        assertTrue(
            descriptor.contains(
                "implementation=\"io.github.amichne.kast.ide.endpoint." +
                    "IdeEndpointProjectActivity\"",
            ),
        )
    }
    @Test
    fun `project endpoint generation is monotonic and exhaustion is closed`() {
        val source = ProjectEndpointGenerationSource.testing(
            refined(IdeRuntimeEpoch.parse(Long.MAX_VALUE)),
        )

        assertEquals(
            Long.MAX_VALUE,
            (source.issue() as ProjectEndpointGenerationIssuance.Issued).epoch.value,
        )
        assertEquals(ProjectEndpointGenerationIssuance.Exhausted, source.issue())
    }
    @Test
    fun `project coordinator coalesces signals retries deferred readiness and publishes unlocked`() =
        withDirectory { directory ->
            val prepared = prepareEndpoint(directory).prepared()
            lateinit var coordinator: IdeEndpointCoordinator
            var publications = 0
            coordinator = IdeEndpointCoordinator(IdeEndpointPublisher {
                assertFalse(Thread.holdsLock(coordinator))
                publications += 1
                IdeEndpointActivation.Rejected(
                    IdeEndpointPublicationFailure.DESCRIPTOR_PUBLICATION_FAILED,
                )
            })

            assertEquals(IdeEndpointServiceStart.Started, coordinator.begin())
            assertEquals(IdeEndpointServiceStart.AlreadyStarted, coordinator.begin())
            assertEquals(IdeEndpointSignalPlan.Coalesced, coordinator.planSignal())
            val firstLaunch = coordinator.listenersInstalled()
            assertTrue(firstLaunch is IdeEndpointSignalPlan.Launch)
            val firstAttempt = (firstLaunch as IdeEndpointSignalPlan.Launch).attempt
            assertEquals(
                IdeEndpointServiceStart.AlreadyStarted,
                coordinator.rejectListenerInstallation(),
            )
            assertEquals(IdeEndpointSignalPlan.Coalesced, coordinator.planSignal())
            val retry = coordinator.planCompletion(
                firstAttempt,
                IdeEndpointStartup.Deferred(IdeEndpointDeferredReadiness.DUMB_MODE),
            )
            assertTrue(retry is IdeEndpointCompletionPlan.Retry)
            val retryAttempt = (retry as IdeEndpointCompletionPlan.Retry).attempt
            val activation = coordinator.planCompletion(
                retryAttempt,
                IdeEndpointStartup.Prepared(prepared),
            )
            assertTrue(activation is IdeEndpointCompletionPlan.Activate)
            val activationRequest =
                (activation as IdeEndpointCompletionPlan.Activate).request
            assertEquals(
                IdeEndpointActivationPlan.Stop,
                coordinator.activate(activationRequest),
            )
            assertEquals(1, publications)
            assertEquals(
                IdeEndpointCompletionPlan.Stop,
                coordinator.planCompletion(
                    retryAttempt,
                    IdeEndpointStartup.Prepared(prepared),
                ),
            )
            assertEquals(
                IdeEndpointActivationPlan.Stop,
                coordinator.activate(activationRequest),
            )
            assertEquals(1, publications)
            assertEquals(IdeEndpointSignalPlan.Terminal, coordinator.planSignal())
        }
    @Test
    fun `duplicate endpoint rejects before a second bind`() = withDirectory { directory ->
        val prepared = prepareEndpoint(directory).prepared()
        var publications = 0
        val service = IdeEndpointOwner(IdeEndpointPublisher {
            publications += 1
            JdkIdeEndpointPublisher.publish(it)
        })
        val first = service.publish(prepared).ready()
        try {
            assertActivationRejected(
                service.publish(prepared),
                IdeEndpointPublicationFailure.DUPLICATE_ENDPOINT,
            )
            assertEquals(1, publications)
        } finally {
            deleteFixture(first)
        }
    }
    @Test
    fun `occupied non socket path is preserved`() = withDirectory { directory ->
        val prepared = prepareEndpoint(directory).prepared()
        val occupied = Path.of(prepared.location.stateDirectoryPath.value)
        Files.writeString(occupied, "owned-by-someone-else")
        assertActivationRejected(
            IdeEndpointOwner(JdkIdeEndpointPublisher).publish(prepared),
            IdeEndpointPublicationFailure.OCCUPIED_NON_SOCKET_PATH,
        )
        assertEquals("owned-by-someone-else", Files.readString(occupied))
        Files.delete(occupied)
    }

    @Test
    fun `reachable socket is preserved`() = withDirectory { directory ->
        val prepared = prepareEndpoint(directory).prepared()
        val stateDirectory = Path.of(prepared.location.stateDirectoryPath.value)
        val socketPath = Path.of(prepared.location.socketPath.value)
        Files.createDirectory(stateDirectory)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { occupied ->
            occupied.bind(UnixDomainSocketAddress.of(socketPath))
            assertActivationRejected(
                IdeEndpointOwner(JdkIdeEndpointPublisher).publish(prepared),
                IdeEndpointPublicationFailure.REACHABLE_OR_OCCUPIED_SOCKET,
            )
            SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                assertTrue(client.connect(UnixDomainSocketAddress.of(socketPath)))
            }
        }
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(stateDirectory)
    }

    @Test
    fun `occupied descriptor is preserved before bind`() = withDirectory { directory ->
        val prepared = prepareEndpoint(directory).prepared()
        val stateDirectory = Path.of(prepared.location.stateDirectoryPath.value)
        val descriptor = Path.of(prepared.location.descriptorPath.value)
        Files.createDirectory(stateDirectory)
        Files.writeString(descriptor, "owned-descriptor")
        assertActivationRejected(
            IdeEndpointOwner(JdkIdeEndpointPublisher).publish(prepared),
            IdeEndpointPublicationFailure.OCCUPIED_DESCRIPTOR_PATH,
        )
        assertEquals("owned-descriptor", Files.readString(descriptor))
        assertFalse(Files.exists(Path.of(prepared.location.socketPath.value)))
        Files.delete(descriptor)
        Files.delete(stateDirectory)
    }

    @Test
    fun `descriptor temporary is created beside the required socket suffix descriptor`() =
        withDirectory { directory ->
            val prepared = prepareEndpoint(directory).prepared()
            val owned = when (val creation = OwnedEndpointDirectory.create(prepared.location)) {
                is OwnedEndpointDirectoryCreation.Created -> creation.directory
                OwnedEndpointDirectoryCreation.Rejected -> fail("exclusive directory rejected")
            }
            val temporary = when (val creation = owned.createDescriptorTemporary()) {
                is OwnedDescriptorTemporaryCreation.Created -> creation.temporary
                OwnedDescriptorTemporaryCreation.Rejected -> fail("temporary rejected")
            }
            assertEquals(
                Path.of(prepared.location.descriptorPath.value).parent,
                temporary.path.parent,
            )
            temporary.delete()
            owned.rollbackEmpty()
            assertFalse(Files.exists(Path.of(prepared.location.stateDirectoryPath.value)))
        }

    @Test
    fun `socket bind failure removes its empty exclusive namespace and cannot publish`() =
        withDirectory { directory ->
            val prepared = prepareEndpoint(directory).prepared()
            assertActivationRejected(
                JdkIdeEndpointPublisher.publishTesting(
                    prepared,
                    IdeEndpointPublicationFault.SOCKET_BIND,
                ),
                IdeEndpointPublicationFailure.SOCKET_BIND_FAILED,
            )
            assertFalse(Files.exists(Path.of(prepared.location.stateDirectoryPath.value)))
            assertFalse(Files.exists(Path.of(prepared.location.descriptorPath.value)))
        }

    @Test
    fun `descriptor publication failure leaves service unpublished and unrelated files untouched`() =
        withDirectory { directory ->
            val prepared = prepareEndpoint(directory).prepared()
            val sentinel = directory.resolve("unrelated")
            Files.writeString(sentinel, "preserve")
            val service = IdeEndpointOwner(IdeEndpointPublisher { candidate ->
                JdkIdeEndpointPublisher.publishTesting(
                    candidate,
                    IdeEndpointPublicationFault.DESCRIPTOR_PUBLICATION,
                )
            })
            assertActivationRejected(
                service.publish(prepared),
                IdeEndpointPublicationFailure.DESCRIPTOR_PUBLICATION_FAILED,
            )
            assertActivationRejected(
                service.publish(prepared),
                IdeEndpointPublicationFailure.DESCRIPTOR_PUBLICATION_FAILED,
            )
            assertEquals("preserve", Files.readString(sentinel))
            assertFalse(Files.exists(Path.of(prepared.location.stateDirectoryPath.value)))
            assertFalse(Files.exists(Path.of(prepared.location.socketPath.value)))
            assertFalse(Files.exists(Path.of(prepared.location.descriptorPath.value)))
            Files.delete(sentinel)
        }
}

internal fun prepareEndpoint(
    directory: Path,
    projectRoot: IdeEndpointCanonicalRoot = endpointRoot("/workspace/kast"),
    descriptorRoot: IdeEndpointCanonicalRoot = projectRoot,
    runtime: HostedEffectsRuntime? = null,
): IdeEndpointPreparation {
    val candidate = compatibilityCandidate()
    val policy = policy(candidate)
    val admitted = when (val admission = policy.admit(candidate)) {
        is IdeHostCompatibilityAdmission.Admitted -> admission.compatibility
        is IdeHostCompatibilityAdmission.Rejected -> fail("compatibility rejected: ${admission.failure}")
    }
    return IdeEndpointPreparation.prepare(
        IdeEndpointPreparationCandidate(
            runtime ?: completeRuntime(projectRoot, admitted),
            policy,
            refined(IdeEndpointLocation.locate(socketDirectory(directory), descriptorRoot)),
            processId(),
            runtimeEpoch(),
        ),
    )
}

private fun completeRuntime(
    root: IdeEndpointCanonicalRoot,
    compatibility: io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility,
): HostedEffectsRuntime {
    val preparation = HostedIdeReadRuntime.prepare(
        HostedIdeReadRuntimeCandidate.Complete(
            HostedIdeReadProject.testing(root, compatibility),
            SemanticReadLease(
                refined(CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root.value))),
                refined(EvidenceGeneration.parse(0)),
            ),
            WorkspaceInspectReadPort { fail("workspace port must not run during publication") },
            SymbolDiscoverReadPort { fail("discover port must not run during publication") },
            SymbolResolveReadPort { fail("resolve port must not run during publication") },
            SymbolDescribeReadPort { fail("describe port must not run during publication") },
        ),
    ) as HostedIdeReadRuntimePreparation.Prepared
    return HostedEffectsRuntime.testing(preparation.runtime)
}

private fun compatibilityCandidate() = IdeHostCompatibilityCandidate(
    ideBuild = "262.9437.185",
    kotlinPluginBuild = "262.9437.185-IJ",
    kastPluginVersion = "1.2.3",
    runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
    operationRegistryDigest = "sha256:" + "1".repeat(64),
    wireSchemaDigest = "sha256:" + "2".repeat(64),
    capabilities = io.github.amichne.kast.protocol.wire.metadata.CanonicalHostedCapabilities
        .candidates
        .map { it.operationId },
)

private fun endpointRoot(value: String) = refined(IdeEndpointCanonicalRoot.parse(value))
private fun socketDirectory(path: Path) = refined(IdeEndpointSocketDirectory.parse(path.toString()))
private fun processId() = refined(IdeProcessId.parse(ProcessHandle.current().pid()))
private fun runtimeEpoch() = refined(IdeRuntimeEpoch.parse(7))
private fun policy(candidate: IdeHostCompatibilityCandidate) =
    refined(IdeHostCompatibilityPolicy.define(candidate))

private fun <Value, Failure> refined(result: Refinement<Value, Failure>): Value = when (result) {
    is Refinement.Refined -> result.value
    is Refinement.Rejected -> fail("fixture refinement rejected: ${result.failure}")
}

internal fun IdeEndpointPreparation.prepared() = when (this) {
    is IdeEndpointPreparation.Prepared -> endpoint
    is IdeEndpointPreparation.Rejected -> fail("endpoint preparation rejected: $failure")
}

internal fun IdeEndpointActivation.ready() = when (this) {
    is IdeEndpointActivation.Ready -> endpoint
    is IdeEndpointActivation.Rejected -> fail("endpoint publication rejected: $failure")
}

private fun assertPreparationRejected(
    result: IdeEndpointPreparation,
    expected: IdeEndpointPublicationFailure,
) = when (result) {
    is IdeEndpointPreparation.Prepared -> fail("unexpected prepared endpoint: ${result.endpoint}")
    is IdeEndpointPreparation.Rejected -> assertEquals(expected, result.failure)
}

private fun assertActivationRejected(
    result: IdeEndpointActivation,
    expected: IdeEndpointPublicationFailure,
) = when (result) {
    is IdeEndpointActivation.Ready -> fail("unexpected ready endpoint: ${result.endpoint}")
    is IdeEndpointActivation.Rejected -> assertEquals(expected, result.failure)
}

internal inline fun withDirectory(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory(Path.of("/tmp"), "kast-endpoint-")
    try {
        block(directory)
    } finally {
        Files.deleteIfExists(directory)
    }
}

private fun assertDirectoryEmpty(directory: Path) {
    Files.newDirectoryStream(directory).use { entries -> assertFalse(entries.iterator().hasNext()) }
}

private fun deleteFixture(endpoint: ReadyIdeEndpoint) {
    endpoint.retire(IdeEndpointRetirementCause.TEST_CLEANUP)
}
