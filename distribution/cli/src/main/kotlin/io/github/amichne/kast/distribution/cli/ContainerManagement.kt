package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal fun containerStore(root: Path): Refinement<ContainerStore, ContainerFailure> =
    when (val platform = ContainerPlatform.current()) {
        is Refinement.Rejected -> platform
        is Refinement.Refined -> Refinement.Refined(ContainerStore(root, platform.value))
    }

internal fun containerDisposition(container: AdmittedContainer): Refinement<ContainerState, ContainerFailure> =
    containerFiles {
        if (Files.notExists(container.stopped, NOFOLLOW_LINKS)) Refinement.Refined(ContainerState.ENABLED)
        else
            when (val record = readContainerRecord<ContainerStopRecord>(container.stopped)) {
                is Refinement.Rejected -> record
                is Refinement.Refined ->
                    if (record.value == ContainerStopRecord(container.id.text))
                        Refinement.Refined(ContainerState.STOPPED)
                    else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            }
    }

/** Explicit semantic use consumes retirement proof before a Host can start again. */
internal fun resumeContainerState(container: AdmittedContainer): Refinement<Unit, ContainerFailure> = containerFiles {
    val retirement = container.directory.resolve("generation-retirement.json")
    if (!Files.notExists(retirement, NOFOLLOW_LINKS)) {
        when (val record = readContainerRetirement(retirement)) {
            is Refinement.Rejected -> return@containerFiles record
            is Refinement.Refined -> Files.delete(retirement)
        }
    }
    when (val state = containerDisposition(container)) {
        is Refinement.Rejected -> state
        is Refinement.Refined -> {
            if (state.value == ContainerState.STOPPED) Files.delete(container.stopped)
            Refinement.Refined(Unit)
        }
    }
}

internal fun shutdownContainerHost(
    container: AdmittedContainer,
    environment: Map<String, String>,
    session: OwnedChildSession,
): Refinement<Unit, ContainerFailure> {
    val installation =
        when (val root = resolveManagementRoot(environment)) {
            is ManagementRootResolution.Rejected -> return Refinement.Rejected(ContainerFailure.RUNTIME_UNAVAILABLE)
            is ManagementRootResolution.Selected -> selectedInstallation(root.root)
        }
    val manifest = readBundledManifest(installation)
    if (
        manifest !is BundledManifestRead.Read ||
            !payloadOwned(installation, manifest.manifest, "share/kast/libexec/kast-service")
    )
        return Refinement.Rejected(ContainerFailure.RUNTIME_UNAVAILABLE)
    containerProgress(ContainerStage.SHUTDOWN, ContainerStageOutcome.STARTED)
    val exit =
        session.run(
            listOf(installation.resolve("share/kast/libexec/kast-service").toString(), "disable"),
            container.home,
            containerEnvironment(container, environment),
            OwnedChildOutput.Diagnostics,
        )
    containerProgress(
        ContainerStage.SHUTDOWN,
        if (exit == 0) ContainerStageOutcome.COMPLETED else ContainerStageOutcome.REJECTED,
    )
    return when (exit) {
        0 -> Refinement.Refined(Unit)
        PublicQuerySignal.INTERRUPT.exit -> Refinement.Rejected(ContainerFailure.CANCELLED_INTERRUPT)
        PublicQuerySignal.TERMINATE.exit -> Refinement.Rejected(ContainerFailure.CANCELLED_TERMINATE)
        else -> Refinement.Rejected(ContainerFailure.SHUTDOWN_FAILED)
    }
}
