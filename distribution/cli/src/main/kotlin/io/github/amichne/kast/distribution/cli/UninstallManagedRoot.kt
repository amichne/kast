package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.contract.InstallationShutdownRequest
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.SerializationException

internal fun captureUninstallManagedRoot(root: Path): InstallationFilesystemIdentity {
    if (
        !Files.isDirectory(root, NOFOLLOW_LINKS) ||
            root.toRealPath() != root ||
            uninstallFilesystemIdentity(root).owner != com.sun.security.auth.module.UnixSystem().uid
    )
        throw ManagementRejected("uninstall-root", "managed root ownership is unproven")
    return uninstallFilesystemIdentity(root)
}

internal fun requireUninstallRootInventory(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    captureUninstallManagedRoot(root)
    val known =
        setOf("installation", "recovery", "activation.lock", "management.lock", "management.json", SHUTDOWN_FENCE)
    Files.newDirectoryStream(root).use { entries ->
        if (entries.any { it.fileName.toString() !in known })
            throw ManagementRejected(
                "uninstall-root",
                "unrecognized managed-root artifacts remain; retained cleanup receipt",
            )
    }
}

internal fun cleanupUninstallActivationLock(root: Path, observe: (UninstallCleanupObservation) -> Unit) {
    uninstallManagedRootEffect(UninstallArtifact.ACTIVATION_LOCK, observe) {
        removeUninstallActivationLock(root)
    }
}

private fun removeUninstallActivationLock(root: Path) {
    val path = root.resolve("activation.lock")
    if (Files.notExists(path, NOFOLLOW_LINKS)) return
    if (
        !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
            Files.getOwner(path) != Files.getOwner(root) ||
            Files.size(path) != 0L
    )
        throw ManagementRejected("uninstall-activation-lock", "activation lock ownership is unproven")
    val identity = uninstallFilesystemIdentity(path)
    FileChannel.open(path, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
        deleteHeldUninstallActivationLock(path, identity, channel)
    }
}

private fun deleteHeldUninstallActivationLock(
    path: Path,
    identity: InstallationFilesystemIdentity,
    channel: FileChannel,
) {
    val held =
        try {
            channel.tryLock()
        } catch (_: java.nio.channels.OverlappingFileLockException) {
            null
        }
            ?: throw ManagementRejected(
                "uninstall-activation-lock",
                "activation is still running; retained cleanup receipt",
            )
    held.use {
        if (uninstallFilesystemIdentity(path) != identity)
            throw ManagementRejected("uninstall-activation-lock", "activation lock identity changed")
        Files.delete(path)
    }
}

internal fun cleanupEmptyUninstallRoot(
    root: Path,
    expected: InstallationFilesystemIdentity,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    uninstallManagedRootEffect(UninstallArtifact.MANAGED_ROOT, observe) {
        if (Files.exists(root, NOFOLLOW_LINKS)) {
            if (captureUninstallManagedRoot(root) != expected)
                throw ManagementRejected(
                    "uninstall-root",
                    "managed root identity changed; retained exterior cleanup journal",
                )
            Files.newDirectoryStream(root).use { entries ->
                if (entries.iterator().hasNext())
                    throw ManagementRejected(
                        "uninstall-root",
                        "managed root is not empty; retained exterior cleanup journal",
                    )
            }
            Files.delete(root)
        }
    }
}

internal fun uninstallFilesystemIdentity(path: Path) =
    InstallationFilesystemIdentity(
        (Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong(),
    )

private inline fun uninstallManagedRootEffect(
    artifact: UninstallArtifact,
    observe: (UninstallCleanupObservation) -> Unit,
    effect: () -> Unit,
) {
    observe(UninstallCleanupObservation.Started(artifact))
    try {
        effect()
    } catch (rejected: ManagementRejected) {
        observe(UninstallCleanupObservation.Rejected(artifact, UninstallCleanupFailure.OWNERSHIP_UNPROVEN))
        throw rejected
    } catch (_: java.io.IOException) {
        observe(UninstallCleanupObservation.Rejected(artifact, UninstallCleanupFailure.FILESYSTEM_REJECTED))
        throw ManagementRejected(
            "uninstall-${artifact.name.lowercase()}",
            "filesystem refused cleanup; retained exterior cleanup journal",
        )
    }
    observe(UninstallCleanupObservation.Completed(artifact))
}

internal fun productRemovalDirectory(root: Path): Path = root.resolve("recovery/installation/product-removal")

internal fun cleanupProductRemovalFence(root: Path) {
    val fence = root.resolve(SHUTDOWN_FENCE)
    if (Files.exists(fence, NOFOLLOW_LINKS)) {
        requireRemoval(Files.notExists(root.resolve("installation"), NOFOLLOW_LINKS))
        requireRemovalPath(fence)
        val identity = uninstallFilesystemIdentity(fence)
        val raw =
            readBoundedFile(fence, 4096L)
                ?: throw ManagementRejected("uninstall-recovery", "retirement fence is unavailable")
        requireRemoval(
            decodeRemovalDocument<InstallationShutdownRequest>(raw) ==
                InstallationShutdownRequest(root.resolve("installation").toString())
        )
        requireRemoval(uninstallFilesystemIdentity(fence) == identity)
        Files.delete(fence)
    }
}

/** Admission covers the interpreter's import tree, not just the launcher being executed. */
internal fun retainedProductInstaller(root: Path, proof: ProductRemovalProof): Path {
    val bundle = productRemovalDirectory(root)
    requireRemovalIdentity(bundle, proof.executionIdentity)
    admitRetainedProductFiles(root, proof, complete = true)
    val control = bundle.resolve("control")
    val interpreter = control.resolve("share/kast/private-python/bin/python3.12")
    val script = control.resolve("share/kast/install-candidate.sh")
    requireRemoval(Files.isExecutable(interpreter) && Files.isRegularFile(script, NOFOLLOW_LINKS))
    return script
}

internal fun cleanupProductRemoval(root: Path, proof: ProductRemovalProof) {
    requireRemoval(
        proof.phase == ProductRemovalPhase.PREPARED && Files.notExists(root.resolve("installation"), NOFOLLOW_LINKS)
    )
    removeEmptyProductStore(root.resolveSibling("kast-runtime-bases"), proof.baseStoreIdentity)
    val bundle = productRemovalDirectory(root)
    if (Files.exists(bundle, NOFOLLOW_LINKS)) {
        requireRemovalIdentity(bundle, proof.executionIdentity)
        val files = admitRetainedProductFiles(root, proof, complete = false)
        val manifest = bundle.resolve("control/installation.json")
        for ((path, expected) in
            files.entries
                .filter { it.key != manifest }
                .sortedBy { it.key.fileName.toString() == "build-record.json" }) {
            requireRetainedFile(path, expected)
            Files.delete(path)
        }
        val directories = removalPaths(bundle).filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
        for (directory in directories.sortedByDescending { it.nameCount }) {
            if (directory != bundle && directory != manifest.parent) {
                requireRemovalPath(directory)
                Files.delete(directory)
            }
        }
        files[manifest]?.let { expected ->
            requireRetainedFile(manifest, expected)
            Files.delete(manifest)
        }
        if (Files.exists(manifest.parent, NOFOLLOW_LINKS)) Files.delete(manifest.parent)
        requireRemovalIdentity(bundle, proof.executionIdentity)
        Files.delete(bundle)
    }
    removeEmptyProductRecoveryParents(root)
}

internal fun requireProductRuntimeStore(root: Path, proof: ProductRemovalProof) {
    val directory = root.resolveSibling("kast-workspaces").resolve("runtimes")
    if (Files.exists(directory, NOFOLLOW_LINKS)) {
        requireRemovalIdentity(directory, proof.runtimeStoreIdentity)
        requireRemoval(Files.list(directory).use { it.findAny().isEmpty })
    }
}

private fun removeEmptyProductStore(directory: Path, identity: InstallationFilesystemIdentity?) {
    if (Files.exists(directory, NOFOLLOW_LINKS)) {
        requireRemovalIdentity(directory, identity)
        Files.delete(directory)
    }
}

private fun removeEmptyProductRecoveryParents(root: Path) {
    for (directory in listOf(root.resolve("recovery/installation"), root.resolve("recovery"))) {
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            requireRemovalPath(directory)
            val empty = Files.list(directory).use { it.findAny().isEmpty }
            if (empty) Files.delete(directory)
        }
    }
}

private data class RetainedFile(val sha256: String, val mode: Int)

private fun admitRetainedProductFiles(
    root: Path,
    proof: ProductRemovalProof,
    complete: Boolean,
): Map<Path, RetainedFile> {
    val bundle = productRemovalDirectory(root)
    val control = bundle.resolve("control")
    val expected = retainedPayloadInventory(root, proof, complete)
    addRetainedEvidence(bundle, proof, expected)
    val directories =
        (expected.keys.flatMap { path ->
                generateSequence(path.parent) { it.parent }.takeWhile { it.startsWith(bundle) }.toList()
            } + bundle + control)
            .toSet()
    val paths = removalPaths(bundle)
    for (path in paths) {
        requireRemovalPath(path)
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) requireRemoval(path in directories)
        else
            requireRetainedFile(
                path,
                expected[path] ?: throw ManagementRejected("uninstall-recovery", "foreign retained file"),
            )
    }
    if (complete) requireRemoval(expected.keys.all { Files.isRegularFile(it, NOFOLLOW_LINKS) })
    return expected.filterKeys { Files.exists(it, NOFOLLOW_LINKS) }
}

private fun retainedPayloadInventory(
    root: Path,
    proof: ProductRemovalProof,
    complete: Boolean,
): MutableMap<Path, RetainedFile> {
    val bundle = productRemovalDirectory(root)
    val control = bundle.resolve("control")
    val manifestPath = control.resolve("installation.json")
    val expected = linkedMapOf<Path, RetainedFile>()
    if (Files.exists(manifestPath, NOFOLLOW_LINKS)) {
        requireRemovalPath(manifestPath)
        requireRemoval(sha256(manifestPath) == proof.installationManifestSha256)
        val manifest =
            when (val read = readBundledManifest(control)) {
                is BundledManifestRead.Read -> read.manifest
                BundledManifestRead.Invalid,
                BundledManifestRead.Unavailable ->
                    throw ManagementRejected("uninstall-recovery", "retained inventory is unavailable")
            }
        requireRemoval(
            manifest.schemaVersion == INSTALLATION_MANIFEST_SCHEMA_VERSION &&
                manifest.installationRoot == root.resolve("installation").toString()
        )
        for (entry in manifest.payloadFiles) {
            val relative = Path.of(entry.path)
            requireRemoval(
                !relative.isAbsolute &&
                    relative.normalize() == relative &&
                    !relative.startsWith("..") &&
                    entry.path.isNotEmpty()
            )
            requireRemoval(entry.sha256.startsWith("sha256:") && productDigest(entry.sha256.removePrefix("sha256:")))
            val path = control.resolve(relative)
            requireRemoval(path != manifestPath && path !in expected)
            expected[path] = RetainedFile(entry.sha256.removePrefix("sha256:"), entry.mode)
        }
        expected[manifestPath] = RetainedFile(proof.installationManifestSha256, removalMode(manifestPath))
    } else requireRemoval(!complete)
    return expected
}

private fun addRetainedEvidence(bundle: Path, proof: ProductRemovalProof, expected: MutableMap<Path, RetainedFile>) {
    for (entry in proof.immutableRemovals) {
        val directory = bundle.resolve("evidence/${entry.kind.name}/${entry.archiveSha256}")
        for ((name, digest) in
            listOf(
                "manifest.json" to entry.manifestSha256,
                "build-record.json" to entry.recordSha256,
                "provenance.json" to entry.provenanceSha256,
            )) {
            expected[directory.resolve(name)] = RetainedFile(digest, PRIVATE_FILE_MODE)
        }
        val recordPath = directory.resolve("build-record.json")
        if (Files.exists(recordPath, NOFOLLOW_LINKS)) {
            requireRetainedFile(recordPath, RetainedFile(entry.recordSha256, PRIVATE_FILE_MODE))
            val raw =
                readBoundedFile(recordPath, 1_048_576L)
                    ?: throw ManagementRejected("uninstall-recovery", "retained product record is unavailable")
            val record = decodeRemovalDocument<ProductBuildRecord>(raw)
            requireRemoval(validProductRecord(record))
            val target =
                record.targets.singleOrNull { target ->
                    when (entry.kind) {
                        ImmutableRemovalKind.RUNTIME -> target.runtime.sha256 == entry.archiveSha256
                        ImmutableRemovalKind.BASE -> target.runtimeBase?.sha256 == entry.archiveSha256
                    }
                } ?: throw ManagementRejected("uninstall-recovery", "retained product identity changed")
            expected[directory.resolve("qualification.json")] =
                RetainedFile(target.qualification.receipt.sha256, PRIVATE_FILE_MODE)
        } else requireRemoval(Files.notExists(directory.resolve("qualification.json"), NOFOLLOW_LINKS))
    }
    proof.mcp?.let { expected[bundle.resolve("mcp-receipt.json")] = RetainedFile(it.receiptSha256, PRIVATE_FILE_MODE) }
}

private fun removalPaths(directory: Path): List<Path> {
    val paths =
        Files.walk(directory).use { it.limit(ControlDistributionLimits.maximumTraversedEntries.toLong() + 1).toList() }
    requireRemoval(paths.size <= ControlDistributionLimits.maximumTraversedEntries)
    return paths
}

private fun requireRetainedFile(path: Path, expected: RetainedFile) {
    requireRemovalPath(path)
    requireRemoval(
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            removalMode(path) == expected.mode &&
            sha256(path) == expected.sha256
    )
}

private fun requireRemovalIdentity(path: Path, expected: InstallationFilesystemIdentity?) {
    requireRemovalPath(path)
    requireRemoval(Files.isDirectory(path, NOFOLLOW_LINKS) && uninstallFilesystemIdentity(path) == expected)
}

private fun requireRemovalPath(path: Path) {
    requireRemoval(
        path.toRealPath() == path &&
            uninstallFilesystemIdentity(path).owner == com.sun.security.auth.module.UnixSystem().uid
    )
    requireRemoval(removalMode(path).and(GROUP_WORLD_WRITE) == 0)
    if (!Files.isDirectory(path, NOFOLLOW_LINKS))
        requireRemoval(
            Files.isRegularFile(path, NOFOLLOW_LINKS) &&
                (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1
        )
}

private fun removalMode(path: Path): Int =
    (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Number).toInt().and(FILE_MODE_MASK)

private inline fun <reified T> decodeRemovalDocument(raw: String): T =
    try {
        managementJson.decodeFromString<T>(raw)
    } catch (_: SerializationException) {
        throw ManagementRejected("uninstall-recovery", "retained removal document is invalid")
    }

private fun requireRemoval(condition: Boolean) {
    if (!condition) throw ManagementRejected("uninstall-recovery", "retained removal ownership is unproven")
}

private const val PRIVATE_FILE_MODE = 384
private const val FILE_MODE_MASK = 511
private const val GROUP_WORLD_WRITE = 18
