package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID

internal fun runContainer(command: ContainerCommand, directory: Path, environment: Map<String, String>): Int {
    val host =
        when (val result = NativeContainerHost.admit(environment)) {
            is Refinement.Rejected -> return rejectContainer(result.failure)
            is Refinement.Refined -> result.value
        }
    val effects =
        containerEnvironment(environment) +
            mapOf(
                "COLIMA_HOME" to host.home.resolve(".colima").toString(),
                "LIMA_HOME" to host.home.resolve(".colima/_lima").toString(),
            )
    val owner = OwnedChildSession()
    val runtime =
        ColimaContainerRuntime(
            host.executable,
            host.home,
            host.architecture,
            NativeContainerProcess(directory, effects, owner),
        )
    val invocation =
        when (val result = containerArguments(command, directory, host.home, runtime, host.taskRunner)) {
            is Refinement.Rejected -> return rejectContainer(result.failure)
            is Refinement.Refined -> result.value
        }
    return try {
        when (invocation) {
            is ContainerInvocation.Status -> owner.run(invocation.arguments, directory, effects)
            is ContainerInvocation.Task -> {
                val cancellation =
                    ContainerCancellation(
                        host.taskRunner,
                        invocation.retained,
                        invocation.execution,
                        directory,
                        effects,
                    )
                owner.run(invocation.arguments, directory, effects, onCancel = cancellation::cancel)
            }
        }
    } catch (_: IOException) {
        rejectContainer(ContainerFailure.PROCESS_START_FAILED)
    }
}

private fun containerArguments(
    command: ContainerCommand,
    directory: Path,
    home: Path,
    runtime: ColimaContainerRuntime,
    taskRunner: List<String>,
): Refinement<ContainerInvocation, ContainerFailure> =
    when (command) {
        ContainerCommand.Status ->
            when (val result = runtime.availability()) {
                is Refinement.Rejected -> result
                is Refinement.Refined ->
                    if (result.value == ContainerRuntimeAvailability.RUNNING)
                        Refinement.Refined(
                            ContainerInvocation.Status(
                                runtime.runner + listOf("ps", "--all", "--filter", "label=$CONTAINER_WORKSPACE_LABEL=1")
                            )
                        )
                    else Refinement.Rejected(ContainerFailure.RUNTIME_STOPPED)
            }
        is ContainerCommand.Open -> openContainerArguments(command, directory, home, runtime, taskRunner)
    }

private fun openContainerArguments(
    command: ContainerCommand.Open,
    directory: Path,
    home: Path,
    runtime: ColimaContainerRuntime,
    taskRunner: List<String>,
): Refinement<ContainerInvocation, ContainerFailure> {
    val workspace =
        when (val result = ContainerWorkspace.discover(directory.resolve(command.directory))) {
            is Refinement.Rejected -> return result
            is Refinement.Refined -> result.value
        }
    val retained =
        when (val result = withContainerLock(home) { runtime.prepare(command, workspace) }) {
            is Refinement.Rejected -> return result
            is Refinement.Refined -> result.value
        }
    if (!retained.running) return Refinement.Rejected(ContainerFailure.PROCESS_FAILED)
    val execution = UUID.randomUUID()
    return Refinement.Refined(
        ContainerInvocation.Task(
            containerExecutionArguments(
                taskRunner,
                retained,
                workspace,
                command.task,
                System.console() != null,
                execution,
            ),
            retained,
            execution,
        )
    )
}

private sealed interface ContainerInvocation {
    val arguments: List<String>

    data class Status(override val arguments: List<String>) : ContainerInvocation

    data class Task(
        override val arguments: List<String>,
        val retained: ContainerPlan.RetainedContainer,
        val execution: UUID,
    ) : ContainerInvocation
}

internal fun containerExecutionArguments(
    runner: List<String>,
    retained: ContainerPlan.RetainedContainer,
    workspace: ContainerWorkspace,
    task: ContainerTask,
    terminal: Boolean,
    execution: UUID,
): List<String> {
    val command =
        when (task) {
            ContainerTask.Shell -> listOf("bash")
            is ContainerTask.Run -> listOf(task.executable) + task.arguments
        }
    return runner +
        listOf("exec", "--exec-id", execution.toString()) +
        (if (terminal) listOf("--tty") else emptyList()) +
        listOf(
            retained.id,
            "/usr/bin/env",
            "GIT_CONFIG_COUNT=1",
            "GIT_CONFIG_KEY_0=safe.directory",
            "GIT_CONFIG_VALUE_0=${workspace.root}",
            "/opt/kast/packaging/kast-task",
            "--reuse",
            "--runtime-root",
            "/opt/kast",
            "--workspace-root",
            workspace.root.parent.toString(),
            workspace.root.toString(),
        ) +
        command
}

private class NativeContainerHost
private constructor(val home: Path, val executable: Path, lima: Path, val architecture: ContainerArchitecture) {
    // Colima and nerdctl exec collapse nonzero statuses. Lima and ctr preserve the task's status.
    val taskRunner =
        listOf(
            lima.toString(),
            "shell",
            "colima-$CONTAINER_PROFILE",
            "--",
            "sudo",
            "ctr",
            "--namespace",
            "default",
            "tasks",
        )

    companion object {
        fun admit(environment: Map<String, String>): Refinement<NativeContainerHost, ContainerFailure> {
            if (System.getProperty("os.name") != "Mac OS X")
                return Refinement.Rejected(ContainerFailure.UNSUPPORTED_PLATFORM)
            val architecture =
                when (val result = ContainerArchitecture.parse(System.getProperty("os.arch"))) {
                    is Refinement.Rejected -> return result
                    is Refinement.Refined -> result.value
                }
            val home =
                when (val result = home(environment)) {
                    is Refinement.Rejected -> return result
                    is Refinement.Refined -> result.value
                }
            return try {
                val directories =
                    environment["PATH"].orEmpty().split(':').filter { it.startsWith('/') }.map { Path.of(it) }
                val executable =
                    executable(directories, "colima") ?: return Refinement.Rejected(ContainerFailure.MISSING_RUNTIME)
                val lima =
                    executable(directories, "limactl") ?: return Refinement.Rejected(ContainerFailure.MISSING_RUNTIME)
                Refinement.Refined(NativeContainerHost(home, executable, lima, architecture))
            } catch (_: InvalidPathException) {
                Refinement.Rejected(ContainerFailure.MISSING_RUNTIME)
            }
        }

        private fun executable(directories: List<Path>, name: String): Path? =
            directories.map { it.resolve(name) }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

        private fun home(environment: Map<String, String>): Refinement<Path, ContainerFailure> =
            try {
                val raw = environment["HOME"]
                if (raw == null) Refinement.Rejected(ContainerFailure.INVALID_HOME)
                else {
                    val path = Path.of(raw)
                    if (!path.isAbsolute || !Files.isDirectory(path)) Refinement.Rejected(ContainerFailure.INVALID_HOME)
                    else Refinement.Refined(path.toRealPath())
                }
            } catch (_: IOException) {
                Refinement.Rejected(ContainerFailure.INVALID_HOME)
            } catch (_: InvalidPathException) {
                Refinement.Rejected(ContainerFailure.INVALID_HOME)
            }
    }
}

private fun rejectContainer(failure: ContainerFailure): Int {
    System.err.println("kast container: outcome=${failure.name}; ${failure.guidance}")
    return failure.exit
}
