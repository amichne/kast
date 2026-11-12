package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** A runtime change can replace only a generation whose exact Host has already retired. */
internal fun prepareContainerGeneration(
    lease: ContainerQueryLease,
    runtime: AdmittedContainerRuntime,
    allowance: ElapsedTimeAllowance,
    waiting: ContainerPreparationWait,
): Refinement<AdmittedContainer, ContainerFailure> =
    withContainerPreparationLock(lease, allowance, waiting) {
        when (val current = lease.admitGeneration()) {
            is Refinement.Rejected -> current
            is Refinement.Refined ->
                if (current.value.runtime.archiveSha256 == runtime.archiveSha256) current
                else
                    ContainerStore(lease.workspace.directory.parent.parent, runtime.manifest.platform)
                        .rebuildRetiredGeneration(current.value, runtime)
        }
    }

internal fun admitContainerRetirement(container: AdmittedContainer): Refinement<Unit, ContainerFailure> {
    val path = container.directory.resolve("generation-retirement.json")
    if (Files.notExists(path, NOFOLLOW_LINKS)) return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
    return when (val record = readContainerRetirement(path)) {
        is Refinement.Rejected -> record
        is Refinement.Refined ->
            if (record.value == retirementBinding(container)) Refinement.Refined(Unit)
            else Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
    }
}

internal fun readContainerRetirement(path: Path): Refinement<ContainerGenerationRetirement, ContainerFailure> =
    when (val record = readContainerRecord<ContainerGenerationRetirement>(path)) {
        is Refinement.Rejected -> record
        is Refinement.Refined ->
            if (
                record.value.schemaVersion == 1 &&
                    runtimeDigest.matches(record.value.workspaceRecordSha256) &&
                    runtimeDigest.matches(record.value.generationRecordSha256)
            )
                record
            else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }

internal fun retirementBinding(container: AdmittedContainer) =
    ContainerGenerationRetirement(
        sha256(container.directory.resolve("container.json")),
        sha256(container.directory.resolve("generation.json")),
    )
