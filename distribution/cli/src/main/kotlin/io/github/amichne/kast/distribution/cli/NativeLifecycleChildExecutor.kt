package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString

private const val SERVICE_DEADLINE_SECONDS = 45L
private const val FRESH_INSTALLER_DEADLINE_SECONDS = 300L
private const val SERVICE_TERMINATION_SECONDS = 5L

@kotlinx.serialization.Serializable
internal enum class LifecycleChildPurpose(val deadlineSeconds: Long) {
    SERVICE(SERVICE_DEADLINE_SECONDS),
    INSTALLER(FRESH_INSTALLER_DEADLINE_SECONDS),
}

internal object NativeLifecycleChildExecutor : LifecycleChildExecutor {
    override fun execute(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
    ): LifecycleChildObservation = executeBounded(command, directory, environment, LifecycleChildPurpose.SERVICE)

    internal fun executeBounded(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
        purpose: LifecycleChildPurpose,
        observe: (LifecycleChildExecutionObservation) -> Unit = {
            System.err.println(managementJson.encodeToString<LifecycleChildExecutionObservation>(it))
        },
    ): LifecycleChildObservation {
        val action = lifecycleChildAction(command, purpose)
        observe(LifecycleChildExecutionObservation.Started(action, purpose))
        val child =
            try {
                startChild(command, directory, environment, purpose)
            } catch (_: Exception) {
                observe(
                    LifecycleChildExecutionObservation.Unavailable(
                        action = action,
                        purpose = purpose,
                        failure = LifecycleChildUnavailability.START_REJECTED,
                    )
                )
                return LifecycleChildObservation.Unavailable
            }
        val observed: LifecycleChildExecutionTerminal =
            try {
                if (child.waitFor(purpose.deadlineSeconds, TimeUnit.SECONDS))
                    LifecycleChildExecutionObservation.Exited(
                        action = action,
                        purpose = purpose,
                        exitCode = child.exitValue(),
                    )
                else {
                    terminateChild(child, purpose)
                    child.waitFor(SERVICE_TERMINATION_SECONDS, TimeUnit.SECONDS)
                    LifecycleChildExecutionObservation.DeadlineExceeded(action, purpose)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                LifecycleChildExecutionObservation.Unavailable(
                    action = action,
                    purpose = purpose,
                    failure = LifecycleChildUnavailability.INTERRUPTED,
                )
            } catch (_: Exception) {
                LifecycleChildExecutionObservation.Unavailable(
                    action = action,
                    purpose = purpose,
                    failure = LifecycleChildUnavailability.OBSERVATION_REJECTED,
                )
            } finally {
                if (child.isAlive) terminateChild(child, purpose)
            }
        observe(observed)
        return observed.asChildObservation()
    }

    private fun startChild(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
        purpose: LifecycleChildPurpose,
    ): Process =
        ProcessBuilder(command)
            .directory(directory.toFile())
            .apply {
                environment().clear()
                environment().putAll(environment)
            }
            // Service diagnostics retain no raw output and cannot block on unread pipes.
            .redirectOutput(
                if (purpose == LifecycleChildPurpose.INSTALLER)
                    ProcessBuilder.Redirect.appendTo(Path.of("/dev/stderr").toFile())
                else ProcessBuilder.Redirect.DISCARD
            )
            .redirectError(
                if (purpose == LifecycleChildPurpose.INSTALLER) ProcessBuilder.Redirect.INHERIT
                else ProcessBuilder.Redirect.DISCARD
            )
            .start()

    private fun terminateChild(child: Process, purpose: LifecycleChildPurpose) {
        if (purpose == LifecycleChildPurpose.INSTALLER) {
            child.descendants().use { descendants ->
                descendants.toList().asReversed().forEach { it.destroyForcibly() }
            }
        }
        child.destroyForcibly()
    }
}
