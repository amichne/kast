package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.ContainerCoordinator
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Path

internal fun interface ContainerProcess {
    fun capture(arguments: List<String>): Refinement<String, ContainerFailure>
}

internal class NativeContainerProcess(
    private val directory: Path,
    private val environment: Map<String, String>,
    private val owner: OwnedChildSession,
) : ContainerProcess {
    override fun capture(arguments: List<String>): Refinement<String, ContainerFailure> {
        val output = OwnedChildOutput.Capture()
        val status =
            try {
                owner.run(arguments, directory, environment, output)
            } catch (_: IOException) {
                return Refinement.Rejected(ContainerFailure.PROCESS_START_FAILED)
            }
        return when {
            status == PublicQuerySignal.INTERRUPT.exit -> Refinement.Rejected(ContainerFailure.INTERRUPTED)
            status == PublicQuerySignal.TERMINATE.exit -> Refinement.Rejected(ContainerFailure.TERMINATED)
            output.result == CapturedChildOutput.Rejected -> Refinement.Rejected(ContainerFailure.OUTPUT_REJECTED)
            status != 0 -> Refinement.Rejected(ContainerFailure.PROCESS_FAILED)
            else ->
                when (val result = output.result) {
                    is CapturedChildOutput.Read -> Refinement.Refined(result.text)
                    CapturedChildOutput.Pending,
                    CapturedChildOutput.Rejected -> Refinement.Rejected(ContainerFailure.OUTPUT_REJECTED)
                }
        }
    }
}

internal fun containerEnvironment(environment: Map<String, String>): Map<String, String> =
    environment.filterKeys { key ->
        key != "SSH" && listOf("DOCKER_", "CONTAINERD_", "NERDCTL_", "COLIMA_", "LIMA_", "GIT_").none(key::startsWith)
    }

/** Preparation is serialized across Kast processes, then the task runs outside this lock. */
internal fun <T> withContainerLock(
    home: Path,
    operation: () -> Refinement<T, ContainerFailure>,
): Refinement<T, ContainerFailure> =
    when (val result = ContainerCoordinator.withLock(home, operation)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> Refinement.Rejected(ContainerFailure.LOCK_UNAVAILABLE)
    }
