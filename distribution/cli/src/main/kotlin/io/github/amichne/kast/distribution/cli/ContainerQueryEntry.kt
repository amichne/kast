package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.encodeToString

/** The actual query child owns a reader lease too, including when its management parent is killed. */
fun withPrivateContainerQuery(state: String?, execute: () -> Unit) {
    val admission = containerFiles { readPrivateQueryEntry(state) }
    when (admission) {
        is Refinement.Rejected -> {
            System.err.println("kast: private query ${admission.failure}")
            println(containerJson.encodeToString<ContainerDocument>(ContainerDocument.Rejected(admission.failure)))
            exitProcess(admission.failure.exit)
        }
        is Refinement.Refined ->
            when (val entry = admission.value) {
                PrivateQueryEntry.DesktopOrArchive -> execute()
                is PrivateQueryEntry.Container -> entry.lease.use { execute() }
            }
    }
}

private fun readPrivateQueryEntry(state: String?): Refinement<PrivateQueryEntry, ContainerFailure> {
    if (state == null) return Refinement.Refined(PrivateQueryEntry.DesktopOrArchive)
    val path = Path.of(state)
    val directory = path.parent
    if (directory == null || directory.parent?.fileName?.toString() != "containers")
        return Refinement.Refined(PrivateQueryEntry.DesktopOrArchive)
    if (!path.isAbsolute || path.toRealPath() != path || path.fileName.toString() != "state")
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    val store =
        when (val selected = containerStore(directory.parent.parent)) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    val container =
        when (val selected = store.select(directory.fileName.toString())) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    return admitPrivateQueryEntry(container)
}

private sealed interface PrivateQueryEntry {
    data object DesktopOrArchive : PrivateQueryEntry

    class Container(val lease: ContainerQueryLease) : PrivateQueryEntry
}

private fun admitPrivateQueryEntry(container: AdmittedContainer): Refinement<PrivateQueryEntry, ContainerFailure> {
    val lease =
        when (val admitted = ContainerQueryLease.acquire(container)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    return when (val disposition = containerDisposition(container)) {
        is Refinement.Rejected -> {
            lease.close()
            disposition
        }
        is Refinement.Refined ->
            if (disposition.value == ContainerState.STOPPED) {
                lease.close()
                Refinement.Rejected(ContainerFailure.STOPPED)
            } else Refinement.Refined(PrivateQueryEntry.Container(lease))
    }
}
