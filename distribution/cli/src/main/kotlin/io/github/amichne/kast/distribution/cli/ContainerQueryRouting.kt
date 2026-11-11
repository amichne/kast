package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.encodeToString

/** One child lifetime spans preparation and query. The registry lock never spans a semantic query. */
internal fun routedContainerQuery(
    arguments: List<String>,
    directory: Path,
    environment: Map<String, String>,
    output: ManagementOutput,
): Int? {
    if (arguments.take(2) == listOf("query", "plan")) return null
    if (
        arguments.firstOrNull() == "describe" ||
            "--help" in arguments ||
            environment.containsKey("KAST_MANAGED_HEADLESS_STATE")
    )
        return null
    val root =
        when (val selected = resolveManagementRoot(environment)) {
            is ManagementRootResolution.Rejected -> return null
            is ManagementRootResolution.Selected -> selected.root.resolveSibling("kast-workspaces")
        }
    if (Files.notExists(root.resolve("containers"), NOFOLLOW_LINKS)) return null
    val session = OwnedChildSession()
    val ready =
        withContainerLock(root) {
            when (val store = containerStore(root)) {
                is Refinement.Rejected -> store
                is Refinement.Refined -> prepareRoutedContainer(store.value, directory, environment, session)
            }
        }
    return when (ready) {
        is Refinement.Refined ->
            runLeasedContainerQuery(ready.value, arguments, directory, environment, session, output)
        is Refinement.Rejected -> rejectContainerQuery(ready.failure, output)
    }
}

private class LeasedContainerQuery(val container: AdmittedContainer, private val lease: ContainerQueryLease) :
    AutoCloseable {
    override fun close() = lease.close()
}

private fun prepareRoutedContainer(
    store: ContainerStore,
    directory: Path,
    environment: Map<String, String>,
    session: OwnedChildSession,
): Refinement<LeasedContainerQuery, ContainerFailure> {
    val container =
        when (val selected = store.route(directory.toRealPath())) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    when (val state = containerDisposition(container)) {
        is Refinement.Rejected -> return state
        is Refinement.Refined ->
            if (state.value == ContainerState.STOPPED) return Refinement.Rejected(ContainerFailure.STOPPED)
    }
    return when (val prepared = prepareContainer(container, environment, session)) {
        is Refinement.Rejected -> prepared
        is Refinement.Refined -> acquireContainerQuery(container)
    }
}

private fun acquireContainerQuery(container: AdmittedContainer): Refinement<LeasedContainerQuery, ContainerFailure> =
    when (val lease = ContainerQueryLease.acquire(container)) {
        is Refinement.Rejected -> lease
        is Refinement.Refined -> Refinement.Refined(LeasedContainerQuery(container, lease.value))
    }

private fun runLeasedContainerQuery(
    query: LeasedContainerQuery,
    arguments: List<String>,
    directory: Path,
    environment: Map<String, String>,
    session: OwnedChildSession,
    output: ManagementOutput,
): Int =
    try {
        query.use { runContainerQuery(it.container, arguments, directory, environment, session, output) }
    } catch (_: java.io.IOException) {
        // The child may already have published its document; never append a second JSON value on stdout.
        System.err.println(
            containerJson.encodeToString<ContainerDocument>(
                ContainerDocument.Rejected(ContainerFailure.FILESYSTEM_UNAVAILABLE)
            )
        )
        ChildOutputDelivery.Rejected.IO.exit
    }

private fun runContainerQuery(
    container: AdmittedContainer,
    arguments: List<String>,
    directory: Path,
    environment: Map<String, String>,
    session: OwnedChildSession,
    output: ManagementOutput,
): Int =
    when (val launcher = selectPublicQueryLauncher(environment)) {
        PublicQueryLauncher.Unavailable -> rejectContainerQuery(ContainerFailure.RUNTIME_UNAVAILABLE, output)
        is PublicQueryLauncher.Admitted ->
            try {
                session.run(
                    listOf(launcher.path.toString()) + arguments,
                    directory,
                    containerEnvironment(container, environment),
                )
            } catch (_: java.io.IOException) {
                rejectContainerQuery(ContainerFailure.RUNTIME_UNAVAILABLE, output)
            }
    }

private fun rejectContainerQuery(failure: ContainerFailure, output: ManagementOutput): Int {
    System.err.println("kast: container $failure; use kast container create or kast container resume <name|id>")
    output.publish(containerJson.encodeToString<ContainerDocument>(ContainerDocument.Rejected(failure)))
    return failure.exit
}
