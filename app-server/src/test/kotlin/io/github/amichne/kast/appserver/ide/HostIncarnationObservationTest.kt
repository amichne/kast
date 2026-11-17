package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.contract.HostExitIndexState
import io.github.amichne.kast.distribution.contract.HostExitReason
import io.github.amichne.kast.distribution.contract.HostExitRecord
import io.github.amichne.kast.distribution.contract.HostedEndpointPaths
import io.github.amichne.kast.distribution.managed.ManagedHeadlessLaunch
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostIncarnationObservationTest {
    @Test
    fun `an owned child Host reports its exit status and the stop its exact lifetime recorded`() {
        ownedChildExit(recordedLifetime = HOST, expectedCause = HostExitCause.RequestedStop(IDLE, SMART))
    }

    @Test
    fun `an owned child exit keeps an unknown cause without a record from its exact lifetime`() {
        ownedChildExit(recordedLifetime = OTHER_HOST, expectedCause = HostExitCause.Unknown)
    }

    @Test
    fun `a live owned child that is not the admitted Host makes ownership ambiguous`() = hostFixture { fixture ->
        val children = HostChildRetention.Retained()
        val launched = ArrayList<Process>()
        val client =
            fixture.client(children) {
                val child = disposableChild(exitCode = 0).also(launched::add)
                val identity = identityOf(fixture, child.toHandle())
                fixture.server.serve(
                    inspected(fixture, identity.copy(processStartEpochMillis = identity.processStartEpochMillis + 1))
                )
                child
            }
        try {
            assertInstanceOf<IdeLifecycleResult.Inspected>(client.startInstallation())
            assertEquals(rejected(HostIncarnationFailure.OWNERSHIP_AMBIGUOUS), client.observeHost(target(fixture)))
        } finally {
            launched.forEach(Process::destroyForcibly)
        }
    }

    @Test
    fun `an ordinary client retains no child and never reports a launched process as owned`() = hostFixture { fixture ->
        val launched = ArrayList<Process>()
        val client =
            fixture.client(HostChildRetention.Discarded) {
                val child = disposableChild(exitCode = 0).also(launched::add)
                fixture.server.serve(inspected(fixture, identityOf(fixture, child.toHandle())))
                child
            }
        try {
            assertInstanceOf<IdeLifecycleResult.Inspected>(client.startInstallation())
            assertEquals(rejected(HostIncarnationFailure.EXECUTABLE_MISMATCH), client.observeHost(target(fixture)))
        } finally {
            launched.forEach(Process::destroyForcibly)
        }
    }

    @Test
    fun `a surviving Host is adopted only by exact process, executable, Host and project`() = hostFixture { fixture ->
        val launcher = fixture.deployment.idea.resolve("bin/idea")
        Files.copy(Path.of("/bin/sleep"), launcher, StandardCopyOption.REPLACE_EXISTING)
        launcher.toFile().setExecutable(true)
        val survivor = ProcessBuilder(launcher.toString(), "60").start()
        try {
            val identity = identityOf(fixture, survivor.toHandle())
            val target = target(fixture)
            val client = fixture.client(HostChildRetention.Retained()) { error("A surviving Host must not launch") }
            rejectForeignAdoptions(fixture, client, target, identity)
            fixture.server.serve(inspected(fixture, identity))
            assertEquals(
                rejected(HostIncarnationFailure.HOST_REPLACED),
                client.observeHost(target.copy(host = OTHER_HOST)),
            )
            val observed =
                assertInstanceOf<ObservedHost.Adopted>(
                    assertInstanceOf<Refinement.Refined<ObservedHost>>(client.observeHost(target)).value
                )
            val expected =
                HostIncarnation(
                    ideaHome = fixture.deployment.idea.toString(),
                    profile = fixture.deployment.selected.installation.config.toString(),
                    hostLifetime = HOST,
                    pid = survivor.pid(),
                    processStartEpochMillis = startMillis(survivor.toHandle()),
                    executable = launcher.toString(),
                )
            assertEquals(expected, observed.incarnation)
            assertEquals(target, observed.target)
            survivor.destroy()
            assertEquals(
                HostExit.Adopted(expected, HostExitCause.Unknown),
                observed.exit.toCompletableFuture().get(EXIT_WAIT_SECONDS, TimeUnit.SECONDS),
            )
            fixture.server.assertOnlyInspections()
        } finally {
            survivor.destroyForcibly()
        }
    }

    private fun rejectForeignAdoptions(
        fixture: HostFixture,
        client: IdeLifecycleClient,
        target: IdeProjectTarget,
        identity: IdeHostRuntimeIdentity,
    ) {
        val cases =
            listOf(
                inspected(fixture, identity.copy(processStartEpochMillis = identity.processStartEpochMillis + 1)) to
                    HostIncarnationFailure.START_MISMATCH,
                inspected(fixture, identityOf(fixture, ProcessHandle.current())) to
                    HostIncarnationFailure.EXECUTABLE_MISMATCH,
                inspected(fixture, identity, projects = listOf(project(target), project(target, "other"))) to
                    HostIncarnationFailure.PROJECT_AMBIGUOUS,
                inspected(fixture, identity, projects = listOf(project(target, "other"))) to
                    HostIncarnationFailure.PROJECT_REPLACED,
                inspected(fixture, identity, projects = emptyList()) to HostIncarnationFailure.PROJECT_ABSENT,
            )
        for ((response, failure) in cases) {
            fixture.server.serve(response)
            assertEquals(rejected(failure), client.observeHost(target))
        }
    }

    private fun ownedChildExit(recordedLifetime: String, expectedCause: HostExitCause) = hostFixture { fixture ->
        val children = HostChildRetention.Retained()
        val launched = ArrayList<Process>()
        val client =
            fixture.client(children) { specification ->
                assertEquals(fixture.deployment.idea.resolve("bin/idea").toString(), specification.arguments.first())
                val child = disposableChild(exitCode = OWNED_EXIT_STATUS).also(launched::add)
                fixture.server.serve(inspected(fixture, identityOf(fixture, child.toHandle())))
                child
            }
        try {
            assertInstanceOf<IdeLifecycleResult.Inspected>(client.startInstallation())
            val child = launched.single()
            val observed =
                assertInstanceOf<ObservedHost.OwnedChild>(
                    assertInstanceOf<Refinement.Refined<ObservedHost>>(client.observeHost(target(fixture))).value
                )
            val expected =
                HostIncarnation(
                    ideaHome = fixture.deployment.idea.toString(),
                    profile = fixture.deployment.selected.installation.config.toString(),
                    hostLifetime = HOST,
                    pid = child.pid(),
                    processStartEpochMillis = startMillis(child.toHandle()),
                    executable = fixture.deployment.idea.resolve("bin/idea").toString(),
                )
            assertEquals(expected, observed.incarnation)
            Files.writeString(
                HostExitRecord.path(fixture.deployment.state),
                Json.encodeToString(HostExitRecord.serializer(), HostExitRecord(IDLE, SMART, recordedLifetime)),
            )
            child.outputStream.use { it.write('\n'.code) }
            assertEquals(
                HostExit.OwnedChild(expected, OWNED_EXIT_STATUS, expectedCause),
                observed.exit.toCompletableFuture().get(EXIT_WAIT_SECONDS, TimeUnit.SECONDS),
            )
            fixture.server.assertOnlyInspections()
        } finally {
            launched.forEach(Process::destroyForcibly)
        }
    }

    private class HostFixture(val root: Path) : AutoCloseable {
        val deployment = ManagedDeploymentFixture(root)
        val server =
            InspectionServer(
                HostedEndpointPaths.directory(
                        deployment.home,
                        HostedEndpointPaths.Family.LIFECYCLE,
                        deployment.idea.toString(),
                    )
                    .resolve("host.sock")
            )

        fun client(children: HostChildRetention, start: (ManagedHeadlessLaunch) -> Process): IdeLifecycleClient =
            IdeLifecycleClient(
                deployment.home,
                deployment.idea,
                LifecycleExchangeTimer.Native,
                System::nanoTime,
                object : IdeLaunchPlatform {
                    override fun startManaged(
                        specification: ManagedHeadlessLaunch
                    ): Refinement<Process, IdeLifecycleFailure> = Refinement.Refined(start(specification))
                },
                deployment.deployment,
                children,
            )

        override fun close() {
            server.close()
            cleanupHostedEndpointFixture(deployment.home)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    /** Serves only inspection; any other command is recorded as a fixture violation. */
    private class InspectionServer(private val socket: Path) : AutoCloseable {
        private val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        private val executor = Executors.newSingleThreadExecutor()
        private val response = AtomicReference<IdeLifecycleResult>()
        private val unexpected = ConcurrentLinkedQueue<IdeLifecycleCommand>()
        private val json = Json { encodeDefaults = true }

        fun serve(result: IdeLifecycleResult) {
            if (response.getAndSet(result) != null) return
            Files.createDirectories(socket.parent)
            channel.bind(UnixDomainSocketAddress.of(socket))
            executor.submit(::loop)
        }

        fun assertOnlyInspections() = assertEquals(emptyList<IdeLifecycleCommand>(), unexpected.toList())

        private fun loop() {
            while (true) {
                val connection =
                    try {
                        channel.accept()
                    } catch (_: IOException) {
                        return
                    }
                connection.use {
                    val input = DataInputStream(Channels.newInputStream(it))
                    val command =
                        json.decodeFromString(
                            IdeLifecycleCommand.serializer(),
                            input.readNBytes(input.readInt()).decodeToString(),
                        )
                    if (command != IdeLifecycleCommand.Inspect) unexpected += command
                    val bytes = json.encodeToString<IdeLifecycleResult>(response.get()).toByteArray()
                    DataOutputStream(Channels.newOutputStream(it)).apply {
                        writeInt(bytes.size)
                        write(bytes)
                        flush()
                    }
                }
            }
        }

        override fun close() {
            channel.close()
            executor.shutdownNow()
            executor.awaitTermination(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val HOST = "00000000-0000-0000-0000-000000000001"
        const val OTHER_HOST = "00000000-0000-0000-0000-000000000009"
        const val PROJECT = "00000000-0000-0000-0000-000000000003"
        const val OWNED_EXIT_STATUS = 7
        const val EXIT_WAIT_SECONDS = 10L
        val IDLE = HostExitReason.IDLE
        val SMART = HostExitIndexState.SMART

        fun hostFixture(test: (HostFixture) -> Unit) {
            HostFixture(Files.createTempDirectory(Path.of("/tmp").toRealPath(), "k").toRealPath()).use(test)
        }

        /** Waits on its standard input, then exits with [exitCode]; the test owns its lifetime. */
        fun disposableChild(exitCode: Int): Process =
            ProcessBuilder("/bin/sh", "-c", "read line; exit $exitCode").start()

        fun startMillis(process: ProcessHandle): Long = process.info().startInstant().orElseThrow().toEpochMilli()

        fun identityOf(fixture: HostFixture, process: ProcessHandle): IdeHostRuntimeIdentity =
            fixture.deployment
                .identity(host = HOST, pid = process.pid())
                .copy(processStartEpochMillis = startMillis(process))

        fun target(fixture: HostFixture) = IdeProjectTarget(HOST, PROJECT, fixture.root.toString())

        fun project(target: IdeProjectTarget, project: String = target.project) =
            IdeProjectDescription(target.copy(project = project), IdeProjectOwnership.MANAGED, 1)

        fun inspected(
            fixture: HostFixture,
            identity: IdeHostRuntimeIdentity,
            projects: List<IdeProjectDescription> = listOf(project(target(fixture))),
        ) =
            IdeLifecycleResult.Inspected(
                HOST,
                fixture.deployment.idea.toString(),
                "IU-262.1",
                projects,
                HostedCompatibilityDocument(
                    "262.10315.125",
                    "262.10315.125-IJ",
                    "0.0.0",
                    CanonicalHostedContract.document,
                ),
                runtimeIdentity = identity,
            )

        fun rejected(failure: HostIncarnationFailure): Refinement<ObservedHost, HostIncarnationFailure> =
            Refinement.Rejected(failure)
    }
}
