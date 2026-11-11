package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.encodeToString

internal fun executeContainerCommand(
    command: ContainerCommand,
    root: Path,
    directory: Path,
    environment: Map<String, String>,
    output: ManagementOutput,
) {
    val session = OwnedChildSession()
    val storage = root.resolveSibling("kast-workspaces")
    val result =
        withContainerLock(storage) {
            when (val store = containerStore(storage)) {
                is Refinement.Rejected -> store
                is Refinement.Refined ->
                    executeContainerOperation(command, store.value, directory, environment, session)
            }
        }
    val document: ContainerDocument =
        when (result) {
            is Refinement.Rejected -> ContainerDocument.Rejected(result.failure)
            is Refinement.Refined -> result.value
        }
    presentContainerDocument(document, containerJsonRequested(command), output)
    if (result is Refinement.Rejected) exitProcess(result.failure.exit)
}

internal fun containerStore(root: Path): Refinement<ContainerStore, ContainerFailure> =
    when (val platform = ContainerPlatform.current()) {
        is Refinement.Rejected -> platform
        is Refinement.Refined -> Refinement.Refined(ContainerStore(root, platform.value))
    }

private fun containerJsonRequested(command: ContainerCommand): Boolean =
    when (command) {
        is ContainerCommand.List -> command.json
        is ContainerCommand.Create -> command.json
        is ContainerCommand.Resume -> command.json
        is ContainerCommand.Stop -> command.json
        is ContainerCommand.Remove -> command.json
        is ContainerCommand.ImportRuntime -> command.json
    }

private fun presentContainerDocument(document: ContainerDocument, json: Boolean, output: ManagementOutput) {
    if (output.structured(json)) {
        output.publish(containerJson.encodeToString(document))
        return
    }
    when (document) {
        is ContainerDocument.Listing -> {
            if (document.containers.isEmpty())
                output.message("No containers. Run kast container create in a trusted workspace.")
            for (summary in document.containers) output.message(
                "${summary.container.name}\t${summary.container.id}\t${summary.state}\t${summary.container.workspace}"
            )
        }
        is ContainerDocument.Created ->
            output.message(
                "Created ${document.container.name} (${document.container.id}); " +
                    "trusted build code at ${document.container.workspace}. The Host starts on demand."
            )
        is ContainerDocument.Completed -> output.message("${document.operation}: ${document.id}")
        is ContainerDocument.Rejected ->
            System.err.println("kast: container ${document.failure}; inspect kast container --help")
    }
}

private fun executeContainerOperation(
    command: ContainerCommand,
    store: ContainerStore,
    directory: Path,
    environment: Map<String, String>,
    session: OwnedChildSession,
): Refinement<ContainerDocument, ContainerFailure> =
    when (command) {
        is ContainerCommand.List -> listContainers(store)
        is ContainerCommand.ImportRuntime ->
            when (
                val imported =
                    importContainerRuntime(
                        Path.of(command.archive).toRealPath(),
                        command.sha256,
                        store.runtimes,
                        MANAGEMENT_VERSION,
                        store.platform,
                    )
            ) {
                is Refinement.Rejected -> imported
                is Refinement.Refined ->
                    Refinement.Refined(
                        ContainerDocument.Completed(ContainerDisposition.RUNTIME_IMPORTED, imported.value.key)
                    )
            }
        is ContainerCommand.Create -> createContainer(command, store, directory)
        is ContainerCommand.Selected -> selectedContainerOperation(command, store, environment, session)
    }

private fun listContainers(store: ContainerStore): Refinement<ContainerDocument, ContainerFailure> {
    val containers =
        when (val all = store.list()) {
            is Refinement.Rejected -> return all
            is Refinement.Refined -> all.value
        }
    val summaries = mutableListOf<ContainerSummary>()
    for (container in containers) {
        val state =
            when (val observed = containerDisposition(container)) {
                is Refinement.Rejected -> return observed
                is Refinement.Refined -> observed.value
            }
        summaries += ContainerSummary(container.record(), state)
    }
    return Refinement.Refined(ContainerDocument.Listing(summaries))
}

private fun createContainer(
    command: ContainerCommand.Create,
    store: ContainerStore,
    directory: Path,
): Refinement<ContainerDocument, ContainerFailure> {
    val creation =
        when (val admitted = store.admitCreation(command, directory)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    val runtime =
        when (val selected = ensureContainerRuntime(store, MANAGEMENT_VERSION)) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    containerProgress(ContainerStage.CREATION, ContainerStageOutcome.STARTED)
    return when (val created = store.create(creation, runtime)) {
        is Refinement.Rejected -> {
            containerProgress(ContainerStage.CREATION, ContainerStageOutcome.REJECTED)
            created
        }
        is Refinement.Refined -> {
            containerProgress(ContainerStage.CREATION, ContainerStageOutcome.COMPLETED)
            Refinement.Refined(ContainerDocument.Created(created.value.record()))
        }
    }
}

private fun selectedContainerOperation(
    command: ContainerCommand.Selected,
    store: ContainerStore,
    environment: Map<String, String>,
    session: OwnedChildSession,
): Refinement<ContainerDocument, ContainerFailure> {
    val container =
        when (val selected = store.select(command.selector)) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    return when (command) {
        is ContainerCommand.Resume ->
            when (val resumed = resumeContainer(container, environment, session)) {
                is Refinement.Rejected -> resumed
                is Refinement.Refined ->
                    Refinement.Refined(ContainerDocument.Completed(ContainerDisposition.RESUMED, container.id.text))
            }
        is ContainerCommand.Stop ->
            when (val stopped = stopContainer(container, environment, session)) {
                is Refinement.Rejected -> stopped
                is Refinement.Refined ->
                    Refinement.Refined(ContainerDocument.Completed(ContainerDisposition.STOPPED, container.id.text))
            }
        is ContainerCommand.Remove ->
            when (val removed = removeContainer(container, environment, session)) {
                is Refinement.Rejected -> removed
                is Refinement.Refined ->
                    Refinement.Refined(ContainerDocument.Completed(ContainerDisposition.REMOVED, container.id.text))
            }
    }
}

private fun resumeContainer(
    container: AdmittedContainer,
    environment: Map<String, String>,
    session: OwnedChildSession,
): Refinement<Unit, ContainerFailure> {
    when (val state = containerDisposition(container)) {
        is Refinement.Rejected -> return state
        is Refinement.Refined -> if (state.value == ContainerState.STOPPED) Files.delete(container.stopped)
    }
    return prepareContainer(container, environment, session)
}

private fun removeContainer(
    container: AdmittedContainer,
    environment: Map<String, String>,
    session: OwnedChildSession,
): Refinement<Unit, ContainerFailure> {
    when (val stopped = stopContainer(container, environment, session)) {
        is Refinement.Rejected -> return stopped
        is Refinement.Refined -> Unit
    }
    // NOFOLLOW walk: repository, explicit Gradle home, and shared runtime remain outside this scope.
    return ContainerQueryLease.whileIdle(container) { eraseContainerState(container) }
}

private fun eraseContainerState(container: AdmittedContainer): Refinement<Unit, ContainerFailure> {
    val detached = container.directory.parent.resolve(".removing-${container.id.text}")
    Files.move(container.directory, detached, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    Files.walk(detached).use { paths ->
        for (path in paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path)
    }
    return Refinement.Refined(Unit)
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

internal fun prepareContainer(
    container: AdmittedContainer,
    environment: Map<String, String>,
    session: OwnedChildSession,
): Refinement<Unit, ContainerFailure> {
    if (!Files.isDirectory(container.workspace)) return Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
    val launcher =
        when (val selected = selectPublicQueryLauncher(environment)) {
            PublicQueryLauncher.Unavailable -> return Refinement.Rejected(ContainerFailure.RUNTIME_UNAVAILABLE)
            is PublicQueryLauncher.Admitted -> selected.path
        }
    containerProgress(ContainerStage.PREPARATION, ContainerStageOutcome.STARTED)
    val exit =
        session.run(
            listOf(launcher.toString(), "workspace", "wait", "--workspace", container.workspace.toString()),
            container.workspace,
            containerEnvironment(container, environment),
            OwnedChildOutput.Diagnostics,
        )
    containerProgress(
        ContainerStage.PREPARATION,
        if (exit == 0) ContainerStageOutcome.COMPLETED else ContainerStageOutcome.REJECTED,
    )
    return containerChildResult(exit, ContainerFailure.PREPARATION_FAILED)
}

private fun stopContainer(
    container: AdmittedContainer,
    environment: Map<String, String>,
    session: OwnedChildSession,
): Refinement<Unit, ContainerFailure> {
    when (val state = containerDisposition(container)) {
        is Refinement.Rejected -> return state
        is Refinement.Refined ->
            if (state.value == ContainerState.ENABLED)
                writeContainerRecord(container.stopped, ContainerStopRecord(container.id.text))
    }
    return ContainerQueryLease.whileIdle(container) { shutdownContainerHost(container, environment, session) }
}

private fun shutdownContainerHost(
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
    return containerChildResult(exit, ContainerFailure.SHUTDOWN_FAILED)
}

private fun containerChildResult(exit: Int, failure: ContainerFailure): Refinement<Unit, ContainerFailure> =
    when (exit) {
        0 -> Refinement.Refined(Unit)
        PublicQuerySignal.INTERRUPT.exit -> Refinement.Rejected(ContainerFailure.CANCELLED_INTERRUPT)
        PublicQuerySignal.TERMINATE.exit -> Refinement.Rejected(ContainerFailure.CANCELLED_TERMINATE)
        else -> Refinement.Rejected(failure)
    }
