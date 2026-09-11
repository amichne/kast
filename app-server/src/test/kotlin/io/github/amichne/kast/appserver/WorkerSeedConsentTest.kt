package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.*
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.kernel.Refinement
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkerSeedConsentTest {
    @Test
    fun `seed consent is bound to one challenge and a finite measured disclosure`() {
        val disclosure = WorkerSeedDisclosure.admit(setOf(WorkerSeedCategory.GLOBAL_VFS), 42).refined()
        val requested = WorkerSeedPrompt.create(disclosure)
        val other = WorkerSeedPrompt.create(disclosure)
        assertEquals(WorkerSeedConsent.GRANTED, requested.decision(requested.reply(WorkerSeedConsent.GRANTED)))
        assertEquals(WorkerSeedConsent.ABSENT, requested.decision(other.reply(WorkerSeedConsent.GRANTED)))
        assertEquals(WorkerSeedConsent.ABSENT, requested.decision("{}"))
        assertInstanceOf(
            Refinement.Rejected::class.java,
            WorkerSeedPrompt.decode(requested.encode().replace("42", "-1")),
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            WorkerSeedPrompt.decode(requested.encode().replace("[\"GLOBAL_VFS\"]", "[\"GLOBAL_VFS\",\"GLOBAL_VFS\"]")),
        )
    }

    @Test
    fun `original runtime connection grants measured seed consent and disconnect never grants`() {
        for (grant in listOf(true, false)) withPayload { root, kast ->
            runBlocking {
                val workspace = Files.createDirectory(root.resolve("workspace"))
                Files.writeString(workspace.resolve("settings.gradle.kts"), "")
                val observed = CompletableDeferred<WorkerSeedConsent>()
                val effects =
                    object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                        override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                            val result =
                                request.consentAuthority.request(
                                    WorkerSeedDisclosure.admit(setOf(WorkerSeedCategory.GLOBAL_VFS), 42).refined()
                                )
                            observed.complete(result)
                            return InstalledWorkerStart.Rejected(WorkerControlFailure.STARTUP_REJECTED)
                        }

                        override suspend fun retireUnpublished(root: Path) = InstalledWorkerRetirement.EXACT_RETIRED
                    }
                val options = InstalledCoordinatorConfiguration.admit(kast, root, emptyMap()).refined()
                val running =
                    (InstalledCoordinator.start(options, effects) as InstalledCoordinatorStart.Started).coordinator
                val client = HttpClient(CIO) { install(WebSockets) }
                try {
                    withTimeout(5_000) {
                        client.webSocket({
                            url("ws://localhost/kast-runtime")
                            unixSocket(options.socket.path.toString())
                        }) {
                            val request =
                                WorkerControlDocument.demand(
                                        workspace,
                                        IndexerHeapSize.Default,
                                        InstalledWorkerStartup.Seed(null, null, WorkerSeedConsentSelection.INTERACTIVE),
                                    )
                                    .copy(configurationIdentity = workerConfigurationIdentity(options.configuration))
                            send(Json.encodeToString(request))
                            val prompt =
                                WorkerSeedPrompt.decode((incoming.receive() as Frame.Text).readText()).refined()
                            assertEquals(42L, prompt.disclosure.estimatedBytes)
                            if (grant) {
                                send(prompt.reply(WorkerSeedConsent.GRANTED))
                                incoming.receive()
                            } else close()
                        }
                    }
                    assertEquals(
                        if (grant) WorkerSeedConsent.GRANTED else WorkerSeedConsent.ABSENT,
                        withTimeout(5_000) { observed.await() },
                    )
                } finally {
                    client.close()
                    running.close()
                }
            }
        }
    }

    private fun withPayload(test: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-seed-").toRealPath()
        try {
            for (directory in listOf("bin", "lib", "share")) Files.createDirectory(root.resolve(directory))
            val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
            test(root, kast)
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Rejected $failure")
    }
