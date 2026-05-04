package io.github.amichne.kast.intellij.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal abstract class KastInstallAction : AnAction() {
    protected abstract fun buildArgs(workspaceRoot: Path): List<String>

    protected abstract fun successMessage(workspaceRoot: Path): String

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workspaceRoot = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return
        val kastBinary = resolveKastBinary() ?: run {
            notify(project, "kast binary not found. Set KAST_CLI_PATH or ensure kast is on PATH.", NotificationType.ERROR)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val process = ProcessBuilder(listOf(kastBinary.toString()) + buildArgs(workspaceRoot))
                .directory(workspaceRoot.toFile())
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(2, TimeUnit.MINUTES)
            when {
                !completed -> {
                    process.destroyForcibly()
                    notify(project, "kast command timed out", NotificationType.ERROR)
                }
                process.exitValue() == 0 -> notify(project, successMessage(workspaceRoot), NotificationType.INFORMATION)
                else -> notify(project, "kast command failed (exit ${process.exitValue()})", NotificationType.ERROR)
            }
        }
    }

    private fun resolveKastBinary(): Path? =
        System.getenv("KAST_CLI_PATH")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Files::isExecutable)
            ?: findOnPath("kast")

    private fun findOnPath(command: String): Path? =
        System.getenv("PATH")
            ?.split(java.io.File.pathSeparator)
            ?.asSequence()
            ?.map { Path.of(it).resolve(command) }
            ?.firstOrNull(Files::isExecutable)

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
