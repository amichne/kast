package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.installationResetFence
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicInstallationResetTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `public reset erases corrupt state legacy artifacts and launcher without executing old commands`() {
        val home = Files.createDirectories(temporary.toRealPath().resolve("home"))
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        Files.writeString(root.resolve("management.json"), "corrupt")
        Files.writeString(installationResetFence(root), "corrupt journal")
        val legacy = Files.createDirectories(home.resolve(".kast"))
        Files.writeString(legacy.resolve("sentinel"), "old state")
        val artifact = Files.createDirectories(root.parent.resolve(".kast-retiring-old"))
        Files.writeString(artifact.resolve("sentinel"), "old payload")
        Files.writeString(root.parent.resolve(".kast-uninstall-old.json"), "corrupt journal")
        val stableLock = root.parent.resolve(".kast-prior.lock")
        Files.writeString(stableLock, "keep inode")
        val key = Files.readAttributes(stableLock, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        val protected = Files.writeString(home.resolve("protected"), "keep")
        Files.createSymbolicLink(root.parent.resolve(".kast-old-link"), protected)
        val command = home.resolve(".local/bin/kast")
        Files.createDirectories(command.parent)
        Files.createSymbolicLink(command, protected)
        val scope = admitted(root, home)
        val runtime = retirementRuntime(listOf(root, artifact, legacy, root))

        val outcome = resetPublicInstallation(scope, execution(runtime))

        assertEquals(ForceResetOutcome.Removed(root.toString()), outcome)
        assertFalse(Files.exists(root))
        assertFalse(Files.exists(legacy))
        assertFalse(Files.exists(artifact))
        assertFalse(Files.exists(command, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(root.parent.resolve(".kast-uninstall-old.json")))
        assertEquals("keep", Files.readString(protected))
        assertEquals(
            key,
            Files.readAttributes(stableLock, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey(),
        )
        runtime.assertConsumed()
    }

    @Test
    fun `public reset accepts a missing launcher and absent legacy state`() {
        val home = Files.createDirectories(temporary.toRealPath().resolve("home"))
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        Files.writeString(root.resolve("management.json"), "corrupt")
        val runtime = retirementRuntime(listOf(root, root))
        assertEquals(
            ForceResetOutcome.Removed(root.toString()),
            resetPublicInstallation(admitted(root, home), execution(runtime)),
        )
        runtime.assertConsumed()
    }

    @Test
    fun `retirement rejection keeps exterior artifacts and does not manufacture a completed reset`() {
        val home = Files.createDirectories(temporary.toRealPath().resolve("home"))
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        Files.writeString(root.resolve("sentinel"), "retained")
        val command = home.resolve(".local/bin/kast")
        Files.createDirectories(command.parent)
        Files.writeString(command, "keep")
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Unavailable),
                ),
            )
        val result = resetPublicInstallation(admitted(root, home), execution(runtime))
        assertTrue(result is ForceResetOutcome.Retained)
        assertEquals("keep", Files.readString(command))
        runtime.assertConsumed()
    }

    @Test
    fun `parent aliases alternate roots and command directories cannot grant reset authority`() {
        val home = Files.createDirectories(temporary.toRealPath().resolve("home"))
        val protected = Files.createDirectories(temporary.toRealPath().resolve("protected"))
        Files.writeString(protected.resolve("sentinel"), "keep")
        Files.createSymbolicLink(home.resolve(".local"), protected)
        assertTrue(
            PublicResetScope.admit(home.resolve(".local/share/kast"), mapOf("HOME" to home.toString()))
                is PublicResetAdmission.Rejected
        )
        assertTrue(PublicResetScope.admit(protected, mapOf("HOME" to home.toString())) is PublicResetAdmission.Rejected)
        Files.delete(home.resolve(".local"))
        Files.createDirectories(home.resolve(".local/bin/kast"))
        assertTrue(
            PublicResetScope.admit(home.resolve(".local/share/kast"), mapOf("HOME" to home.toString()))
                is PublicResetAdmission.Rejected
        )
        assertEquals("keep", Files.readString(protected.resolve("sentinel")))
    }

    private fun admitted(root: Path, home: Path): PublicResetScope =
        (PublicResetScope.admit(root, mapOf("HOME" to home.toString())) as PublicResetAdmission.Admitted).scope

    private fun execution(runtime: ForceDaemonRuntime) =
        ForceResetExecution(
            runtime = runtime,
            installer = ForceReinstaller { _, _, _ -> error("old or fresh installer must not run during reset") },
            observe = {},
        )

    private fun retirementRuntime(roots: List<Path>) = PublicRetirementScript(roots)
}

private class PublicRetirementScript(roots: List<Path>) : ForceDaemonRuntime {
    private val scripts = ArrayDeque(roots.map { ResetRuntimeScript(it, closedRetirementSteps()) })
    private var calls = 0

    private fun <T> next(action: (ResetRuntimeScript) -> T): T {
        val script = scripts.firstOrNull() ?: error("unexpected retirement")
        val result = action(script)
        calls++
        if (calls == 4) {
            script.assertConsumed()
            scripts.removeFirst()
            calls = 0
        }
        return result
    }

    fun assertConsumed() = assertTrue(scripts.isEmpty(), "unconsumed retirements")

    override fun launch(command: ResetLaunchCommand, home: Path) = next { it.launch(command, home) }

    override fun processes() = next { it.processes() }

    override fun capture(process: ResetScopedProcess) = error("unexpected socket capture")

    override fun retire(capture: ResetSharedSocketCapture.Captured, allowance: Duration) =
        error("unexpected process signal")

    override fun activate(installation: Path, home: Path, environment: Map<String, String>) =
        error("unexpected activation")

    override fun readiness(installation: Path) = error("unexpected readiness")
}
