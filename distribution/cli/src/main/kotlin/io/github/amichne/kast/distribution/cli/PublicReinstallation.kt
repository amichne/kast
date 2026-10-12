package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Installed force reinstall uses the same paired public installer as a downloaded bootstrap. */
internal fun reinstallPublicInstallation(
    scope: PublicResetScope,
    environment: Map<String, String>,
    executor: LifecycleChildExecutor = NativeForceInstallerExecutor,
    runtime: ForceDaemonRuntime = NativeForceDaemonRuntime,
): ForceResetOutcome {
    val channel =
        when (val receipt = readReceipt(scope.root)) {
            is ReceiptRead.Read -> receipt.receipt.channel
            is ReceiptRead.Unavailable -> ReleaseChannel.STABLE
        }
    val temporary =
        try {
            Files.createTempDirectory("kast-public-reinstall-")
        } catch (_: IOException) {
            return publicReinstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
        }
    val outcome =
        try {
            runPublicReinstaller(scope, channel, environment, temporary, executor, runtime)
        } catch (_: IOException) {
            publicReinstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            publicReinstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
        }
    return try {
        Files.walkFileTree(temporary, ResetDeletingVisitor)
        outcome
    } catch (_: IOException) {
        publicReinstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
    } catch (_: SecurityException) {
        publicReinstallRejected(ForceResetFailure.FILESYSTEM_REJECTED)
    }
}

private fun runPublicReinstaller(
    scope: PublicResetScope,
    channel: ReleaseChannel,
    environment: Map<String, String>,
    temporary: Path,
    executor: LifecycleChildExecutor,
    runtime: ForceDaemonRuntime,
): ForceResetOutcome {
    val script = temporary.resolve("install.sh")
    when (val copied = materializeBootstrap(script)) {
        ResetEffect.Completed -> Unit
        is ResetEffect.Rejected -> return publicReinstallRejected(copied.failure)
    }
    val report = temporary.resolve("result.json")
    val arguments =
        listOf("/bin/bash", script.toString(), "--force", "--skip-codex-mcp") +
            when (channel) {
                ReleaseChannel.STABLE -> emptyList()
                ReleaseChannel.DEVELOPER -> listOf("--developer-latest")
            }
    val childEnvironment =
        environment.filterKeys { it in setOf("PATH", "LANG", "LC_ALL", "TMPDIR", "NO_COLOR", "CODEX_HOME") } +
            mapOf(
                "HOME" to scope.home.toString(),
                "XDG_CONFIG_HOME" to scope.command.parent.toString(),
                "KAST_MANAGEMENT_REPORT_PATH" to report.toString(),
            )
    when (val child = executor.execute(arguments, scope.home, childEnvironment)) {
        is LifecycleChildObservation.Exited ->
            if (child.code != 0) return publicReinstallRejected(ForceResetFailure.INSTALLER_REJECTED)
        LifecycleChildObservation.Unavailable -> return publicReinstallRejected(ForceResetFailure.INSTALLER_UNAVAILABLE)
        LifecycleChildObservation.DeadlineExceeded ->
            return publicReinstallRejected(ForceResetFailure.INSTALLER_DEADLINE_EXCEEDED)
    }
    val fresh =
        when (val admitted = FreshResetInstallation.admitPublic(scope, report)) {
            is FreshResetResult.Prepared -> admitted.installation
            is FreshResetResult.Rejected -> return publicReinstallRejected(admitted.failure)
        }
    return when (val ready = runtime.readiness(fresh.installation)) {
        is ResetActivationObservation.Ready ->
            if (ready.version == fresh.version.value) {
                ForceResetOutcome.Reinstalled(
                    fresh.installation.toString(),
                    fresh.version.value,
                    fresh.command.toString(),
                    ready.generation,
                )
            } else pendingPublicReinstall(fresh)
        ResetActivationObservation.Unavailable -> pendingPublicReinstall(fresh)
    }
}

private fun publicReinstallRejected(failure: ForceResetFailure) =
    ForceResetOutcome.Rejected(ForceResetOperation.REINSTALL, ForceResetStage.INSTALLATION, failure)

private fun pendingPublicReinstall(fresh: FreshResetInstallation) =
    ForceResetOutcome.Pending(
        fresh.installation.toString(),
        fresh.version.value,
        fresh.command.toString(),
        ForceResetFailure.READINESS_REJECTED,
    )
