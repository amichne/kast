package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Only the fresh installation boundary runs after destructive cleanup. */
internal fun interface ForceReinstaller {
    fun stage(erased: ErasedReset, home: Path, environment: Map<String, String>): FreshResetResult
}

internal object BundledForceReinstaller : ForceReinstaller {
    override fun stage(erased: ErasedReset, home: Path, environment: Map<String, String>): FreshResetResult =
        installFresh(erased, home, environment, NativeForceInstallerExecutor)
}

internal fun installFresh(
    erased: ErasedReset,
    home: Path,
    environment: Map<String, String>,
    executor: LifecycleChildExecutor,
): FreshResetResult {
    when (val admitted = erased.stagingAdmission()) {
        ResetEffect.Completed -> Unit
        is ResetEffect.Rejected -> return FreshResetResult.Rejected(admitted.failure)
    }
    val root = erased.root
    val temporary =
        try {
            Files.createTempDirectory("kast-reset-")
        } catch (_: IOException) {
            return resetInstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
        }
    val outcome =
        try {
            runFreshInstaller(root, home, environment, temporary, executor)
        } catch (_: IOException) {
            resetInstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            resetInstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
        }
    return try {
        Files.deleteIfExists(temporary.resolve("install.sh"))
        Files.deleteIfExists(temporary.resolve("result.json"))
        Files.deleteIfExists(temporary)
        outcome
    } catch (_: IOException) {
        resetInstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
    } catch (_: SecurityException) {
        resetInstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
    }
}

private fun runFreshInstaller(
    root: Path,
    home: Path,
    environment: Map<String, String>,
    temporary: Path,
    executor: LifecycleChildExecutor,
): FreshResetResult {
    val script = temporary.resolve("install.sh")
    val resource =
        ForceReinstaller::class.java.getResourceAsStream("/management/bootstrap-install.sh")
            ?: return resetInstallRejected(ForceResetFailure.INSTALLER_UNAVAILABLE)
    resource.use { Files.copy(it, script) }
    // All new command bytes live under the freshly selected directory. No old exterior executable is trusted.
    val childEnvironment =
        environment.filterKeys { it in setOf("PATH", "LANG", "LC_ALL", "TMPDIR", "NO_COLOR") } +
            mapOf(
                "HOME" to home.toString(),
                "XDG_CONFIG_HOME" to root.resolve("bin").toString(),
                "KAST_MANAGEMENT_REPORT_PATH" to temporary.resolve("result.json").toString(),
            )
    val command =
        listOf(
            "/bin/bash",
            script.toString(),
            "--install-root",
            root.toString(),
            "--force",
            "--skip-codex-mcp",
            "--stage-only",
        )
    return when (val result = executor.execute(command, home, childEnvironment)) {
        is LifecycleChildObservation.Exited ->
            if (result.code == 0) FreshResetInstallation.admit(root, temporary.resolve("result.json"))
            else resetInstallRejected(ForceResetFailure.INSTALLER_REJECTED)
        LifecycleChildObservation.DeadlineExceeded ->
            resetInstallRejected(ForceResetFailure.INSTALLER_DEADLINE_EXCEEDED)
        LifecycleChildObservation.Unavailable -> resetInstallRejected(ForceResetFailure.INSTALLER_UNAVAILABLE)
    }
}

private fun resetInstallRejected(failure: ForceResetFailure) = FreshResetResult.Rejected(failure)

internal object NativeForceInstallerExecutor : LifecycleChildExecutor {
    override fun execute(command: List<String>, directory: Path, environment: Map<String, String>) =
        NativeLifecycleChildExecutor.executeBounded(command, directory, environment, LifecycleChildPurpose.INSTALLER)
}
