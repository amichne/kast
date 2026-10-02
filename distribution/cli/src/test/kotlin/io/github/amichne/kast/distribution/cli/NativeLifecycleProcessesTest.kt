package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeLifecycleProcessesTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `native stop force retires only the recorded disposable installed JVM and verifies OS exit`() {
        val fixture = createLifecycleFixture(temporary)
        val jar = createChildJar(fixture.installation)
        qualifyChildJar(fixture, jar)
        val child =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp",
                    jar.toString(),
                    "io.github.amichne.kast.cli.rpc.KastToolRpcMain",
                )
                .redirectError(temporary.resolve("child-error.log").toFile())
                .start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            assertEquals("ready", reader.submit<String> { child.inputReader().readLine() }.get(5, TimeUnit.SECONDS))
            val start = child.toHandle().info().startInstant().orElseThrow().toEpochMilli()
            val directory = Files.createDirectories(fixture.installation.resolve("state/run/one-shot"))
            Files.writeString(
                directory.resolve("child.json"),
                managementJson.encodeToString(InstalledToolInvocation(1, child.pid(), start)),
            )
            val result =
                executeInstallationLifecycle(
                    fixture.root,
                    fixture.home,
                    emptyMap(),
                    LifecycleOperation.STOP_FORCE,
                    LifecycleExecution(
                        child = LifecycleChildExecutor { _, _, _ -> LifecycleChildObservation.Exited(0) },
                        processes =
                            object : LifecycleProcesses by NativeLifecycleProcesses {
                                override fun selectedHost(executable: Path): LifecycleEffect {
                                    assertEquals(
                                        temporary.toRealPath().resolve("IDE.app/Contents/MacOS/idea"),
                                        executable,
                                    )
                                    return LifecycleEffect.Completed
                                }
                            },
                        observe = {},
                    ),
                )
            assertEquals(
                LifecycleOutcome.Stopped(LifecycleOperation.STOP_FORCE, fixture.installation.toString()),
                result,
            )
            assertFalse(child.isAlive)
            assertEquals(LifecycleProcessObservation.Absent, NativeLifecycleProcesses.observe(child.pid()))
        } finally {
            child.destroyForcibly()
            reader.shutdownNow()
        }
    }

    @Test
    fun `force reset native adapter retires an exactly scoped disposable JVM without receipt ownership`() {
        withResetChild { fixture, child ->
            val observed = NativeLifecycleProcesses.observe(child.pid()) as LifecycleProcessObservation.Present
            val selected = ResetScopedProcess.select(observed.snapshot, fixture.root) as ResetProcessSelection.Selected
            val observations = mutableListOf<ResetProcessObservation>()
            assertEquals(
                LifecycleProcessObservation.Absent,
                NativeForceDaemonRuntime.retireObserved(
                    selected.process,
                    java.time.Duration.ofSeconds(5),
                    observations::add,
                ),
            )
            assertEquals(
                listOf(
                    ResetProcessObservation(
                        child.pid(),
                        ResetProcessStage.REVALIDATE,
                        ResetProcessResult.IDENTITY_VERIFIED,
                    ),
                    ResetProcessObservation(child.pid(), ResetProcessStage.VERIFY, ResetProcessResult.RETIRED),
                ),
                observations,
            )
            assertFalse(child.isAlive)
        }
    }

    @Test
    fun `native identity mismatch emits finite stage evidence and never signals the child`() {
        withResetChild { fixture, child ->
            val observed = NativeLifecycleProcesses.observe(child.pid()) as LifecycleProcessObservation.Present
            val altered =
                ResetScopedProcess.select(
                    observed.snapshot.copy(arguments = observed.snapshot.arguments + "unexpected"),
                    fixture.root,
                ) as ResetProcessSelection.Selected
            val observations = mutableListOf<ResetProcessObservation>()
            assertEquals(
                LifecycleProcessObservation.Unavailable,
                NativeForceDaemonRuntime.retireObserved(
                    altered.process,
                    java.time.Duration.ofSeconds(5),
                    observations::add,
                ),
            )
            assertEquals(
                listOf(
                    ResetProcessObservation(
                        child.pid(),
                        ResetProcessStage.REVALIDATE,
                        ResetProcessResult.IDENTITY_REJECTED,
                    )
                ),
                observations,
            )
            assertTrue(child.isAlive)
        }
    }

    private fun withResetChild(block: (LifecycleFixture, Process) -> Unit) {
        val fixture = createLifecycleFixture(temporary)
        val jar = createChildJar(fixture.installation)
        val child =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp",
                    jar.toString(),
                    "io.github.amichne.kast.cli.rpc.KastToolRpcMain",
                )
                .start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            assertEquals("ready", reader.submit<String> { child.inputReader().readLine() }.get(5, TimeUnit.SECONDS))
            block(fixture, child)
        } finally {
            child.destroyForcibly()
            reader.shutdownNow()
        }
    }

    private fun qualifyChildJar(fixture: LifecycleFixture, jar: Path) {
        val manifest = (readBundledManifest(fixture.installation) as BundledManifestRead.Read).manifest
        Files.writeString(
            fixture.installation.resolve("installation.json"),
            managementJson.encodeToString(
                FixtureLifecycleManifest(
                    3,
                    "1.2.3",
                    fixture.installation.toString(),
                    manifest.payloadFiles.map {
                        if (it.path == "lib/kast.jar") it.copy(sha256 = "sha256:${sha256(jar)}") else it
                    },
                )
            ),
        )
    }

    private fun createChildJar(installation: Path): Path {
        val source = temporary.resolve("KastToolRpcMain.java")
        Files.writeString(source, checkNotNull(javaClass.getResource("/lifecycle/KastToolRpcMain.java")).readText())
        val classes = Files.createDirectory(temporary.resolve("classes"))
        assertEquals(
            0,
            ToolProvider.getSystemJavaCompiler()
                .run(
                    null,
                    null,
                    null,
                    "-d",
                    classes.toString(),
                    source.toString(),
                ),
        )
        val relative = "io/github/amichne/kast/cli/rpc/KastToolRpcMain.class"
        val jar = installation.resolve("lib/kast.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry(relative))
            it.write(Files.readAllBytes(classes.resolve(relative)))
            it.closeEntry()
        }
        return jar
    }
}
