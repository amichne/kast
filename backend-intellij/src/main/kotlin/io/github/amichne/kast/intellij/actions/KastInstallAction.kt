package io.github.amichne.kast.intellij.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal abstract class KastInstallAction : AnAction() {
    protected abstract fun buildArgs(workspaceRoot: Path): List<String>

    protected abstract fun successMessage(workspaceRoot: Path): String

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workspaceRoot = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return
        val kastBinary = when (val resolution = resolveConfiguredKastBinary(workspaceRoot)) {
            is KastBinaryResolution.Found -> resolution.path
            is KastBinaryResolution.NotExecutable -> {
                notify(project, resolution.message, NotificationType.ERROR)
                return
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            when (val result = runKastInstallCommand(kastBinary, workspaceRoot, buildArgs(workspaceRoot))) {
                is KastInstallCommandResult.Success -> notify(
                    project,
                    successMessage(workspaceRoot),
                    NotificationType.INFORMATION
                )
                is KastInstallCommandResult.TimedOut -> notify(
                    project,
                    "kast command timed out",
                    NotificationType.ERROR
                )
                is KastInstallCommandResult.Failed -> {
                    notify(project, "kast command failed (exit ${result.exitCode})", NotificationType.ERROR)
                }
            }
        }
    }

    private fun notify(
        project: Project,
        message: String,
        type: NotificationType,
    ) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Kast")
                .createNotification(message, type)
                .notify(project)
        }
    }
}

internal sealed interface KastBinaryResolution {
    data class Found(val path: Path) : KastBinaryResolution

    data class NotExecutable(val path: Path) : KastBinaryResolution {
        val message: String =
            "kast binary configured at $path is missing or not executable. " +
            "Set [cli] binaryPath in config.toml to an executable kast binary."
    }
}

internal sealed interface KastInstallCommandResult {
    data object Success : KastInstallCommandResult

    data object TimedOut : KastInstallCommandResult

    data class Failed(
        val exitCode: Int,
        val output: String,
    ) : KastInstallCommandResult
}

internal fun resolveConfiguredKastBinary(
    workspaceRoot: Path,
    configLoader: (Path) -> KastConfig = KastConfig::load,
): KastBinaryResolution {
    val binaryPath = Path.of(configLoader(workspaceRoot).cli.binaryPath.value).toAbsolutePath().normalize()
    return if (Files.isExecutable(binaryPath)) {
        KastBinaryResolution.Found(binaryPath)
    } else {
        KastBinaryResolution.NotExecutable(binaryPath)
    }
}

internal fun runKastInstallCommand(
    kastBinary: Path,
    workspaceRoot: Path,
    args: List<String>,
    timeout: Long = 2,
    timeoutUnit: TimeUnit = TimeUnit.MINUTES,
): KastInstallCommandResult {
    val process = ProcessBuilder(listOf(kastBinary.toString()) + args)
        .directory(workspaceRoot.toFile())
        .redirectErrorStream(true)
        .start()
    val completed = process.waitFor(timeout, timeoutUnit)
    if (!completed) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        return KastInstallCommandResult.TimedOut
    }

    val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
    return if (process.exitValue() == 0) {
        KastInstallCommandResult.Success
    } else {
        KastInstallCommandResult.Failed(
            exitCode = process.exitValue(),
            output = output,
        )
    }
}
