package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption

/** Migration retains live profile paths and never starts, stops, or trusts a Host. */
internal fun migrateLegacyWorkspace(
    store: ContainerStore,
    workspace: AdmittedWorkspace,
): Refinement<AdmittedWorkspace, ContainerFailure> =
    when (workspace.generation) {
        ContainerGenerationSource.Current -> Refinement.Refined(workspace)
        is ContainerGenerationSource.Legacy ->
            when (val generation = store.admitGeneration(workspace)) {
                is Refinement.Rejected -> generation
                is Refinement.Refined ->
                    ContainerQueryLease.whileIdle(generation.value) { publishMigration(store, generation.value) }
            }
    }

/** Only the record rename exposes current state; interrupted sidecars remain bound to v1 facts. */
private fun publishMigration(
    store: ContainerStore,
    container: AdmittedContainer,
): Refinement<AdmittedWorkspace, ContainerFailure> = containerFiles {
    when (val trust = retainTrust(container)) {
        is Refinement.Rejected -> return@containerFiles trust
        is Refinement.Refined -> Unit
    }
    when (val generation = retainGeneration(container)) {
        is Refinement.Rejected -> return@containerFiles generation
        is Refinement.Refined -> Unit
    }
    val temporary = container.directory.resolve(".migration-${java.util.UUID.randomUUID()}")
    writeContainerRecord(temporary, container.record())
    Files.move(
        temporary,
        container.directory.resolve("container.json"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
    store.readWorkspace(container.directory)
}

private fun retainTrust(container: AdmittedContainer): Refinement<Unit, ContainerFailure> {
    val plugins =
        when (val selected = selectedContainerPlugins(container)) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    val trust = plugins.parent.resolve("options/trusted-paths.xml")
    when (val admitted = admitContainerTrust(trust, container.workspace)) {
        is Refinement.Rejected -> return admitted
        is Refinement.Refined -> Unit
    }
    val durable = container.directory.resolve("trust.xml")
    if (Files.notExists(durable, NOFOLLOW_LINKS)) Files.copy(trust, durable)
    return admitContainerTrust(durable, container.workspace)
}

private fun retainGeneration(container: AdmittedContainer): Refinement<Unit, ContainerFailure> {
    val generation = container.directory.resolve("generation.json")
    val expected = ContainerGenerationRecord(container.runtime.key, container.runtime.archiveSha256)
    if (Files.notExists(generation, NOFOLLOW_LINKS)) writeContainerRecord(generation, expected)
    return when (val recorded = readContainerRecord<ContainerGenerationRecord>(generation)) {
        is Refinement.Rejected -> recorded
        is Refinement.Refined ->
            if (recorded.value == expected) Refinement.Refined(Unit)
            else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }
}
