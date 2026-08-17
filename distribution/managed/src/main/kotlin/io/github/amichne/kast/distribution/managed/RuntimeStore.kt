package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.RuntimeArchitecture
import io.github.amichne.kast.distribution.contract.RuntimePlatform
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSource
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLockInterruptionException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

internal const val RECEIPT_NAME = ".kast-runtime-receipt"

enum class RuntimeStoreFailure {
    STORE_INVALID,
    ARTIFACT_UNAVAILABLE,
    DIGEST_MISMATCH,
    ARCHIVE_REJECTED,
    LAYOUT_INVALID,
    RUNTIME_INCOMPATIBLE,
    INTERRUPTED,
}

/** An absolute normalized parent for exact content-addressed runtime identities. */
class RuntimeStore private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition: `Path -> RuntimeStoreAdmission`.
         *
         * Establishes an absolute physically canonical, non-symlinked store path without creating it.
         * [RuntimeStoreFailure.STORE_INVALID] is the closed expected failure. The path may leave
         * only at the managed filesystem boundary.
         */
        fun admit(path: Path): RuntimeStoreAdmission {
            val absolute = path.normalize()
            if (!absolute.isAbsolute || Files.isSymbolicLink(absolute)) {
                return RuntimeStoreAdmission.Rejected(RuntimeStoreFailure.STORE_INVALID)
            }
            val canonical = try {
                if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                    absolute.toRealPath()
                } else {
                    val missing = ArrayDeque<Path>()
                    var ancestor = absolute
                    while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                        missing.addFirst(ancestor.fileName)
                        ancestor = ancestor.parent ?: return RuntimeStoreAdmission.Rejected(
                            RuntimeStoreFailure.STORE_INVALID,
                        )
                    }
                    missing.fold(ancestor.toRealPath(), Path::resolve)
                }
            } catch (_: IOException) {
                return RuntimeStoreAdmission.Rejected(RuntimeStoreFailure.STORE_INVALID)
            } catch (_: SecurityException) {
                return RuntimeStoreAdmission.Rejected(RuntimeStoreFailure.STORE_INVALID)
            }
            return if (!Files.isSymbolicLink(canonical)) {
                RuntimeStoreAdmission.Admitted(RuntimeStore(canonical))
            } else {
                RuntimeStoreAdmission.Rejected(RuntimeStoreFailure.STORE_INVALID)
            }
        }
    }
}

sealed interface RuntimeStoreAdmission {
    data class Admitted(val store: RuntimeStore) : RuntimeStoreAdmission
    data class Rejected(val failure: RuntimeStoreFailure) : RuntimeStoreAdmission
}

/** A verified executable and layout installed under its exact runtime identity. */
class InstalledSemanticRuntime internal constructor(
    val runtimeId: SemanticRuntimeId,
    val directory: Path,
    val executable: Path,
)

sealed interface SemanticRuntimeResolution {
    data class Installed(val runtime: InstalledSemanticRuntime) : SemanticRuntimeResolution
    data class Rejected(val failure: RuntimeStoreFailure) : SemanticRuntimeResolution
}

/** Sole adapter for realizing a semantic runtime into the content-addressed store. */
class ManagedSemanticRuntimeProvider(
    private val store: RuntimeStore,
    private val downloader: RuntimeArtifactDownloader = JdkRuntimeArtifactDownloader,
) {
    private companion object {
        val processLocks = ConcurrentHashMap<Path, ReentrantLock>()
    }

    /**
     * Proof transition: `SemanticRuntimeManifest + SemanticRuntimeSource ->
     * SemanticRuntimeResolution`.
     *
     * Establishes an exact digest-verified archive, safe extracted layout, atomic installation,
     * and re-admitted executable. [RuntimeStoreFailure] is the closed expected failure. Raw paths
     * and network streams are permitted only inside this managed adapter.
     */
    fun resolve(
        manifest: SemanticRuntimeManifest,
        source: SemanticRuntimeSource,
    ): SemanticRuntimeResolution {
        when (admitHost(manifest.platform, manifest.architecture)) {
            HostAdmission.Compatible -> Unit
            HostAdmission.Incompatible -> return SemanticRuntimeResolution.Rejected(
                RuntimeStoreFailure.RUNTIME_INCOMPATIBLE,
            )
        }
        val preparation = prepareStore(store.path)
        if (preparation is StorePreparation.Rejected) {
            return SemanticRuntimeResolution.Rejected(preparation.failure)
        }
        val storeKey = manifest.runtimeId.storeKey()
        val runtimeDirectory = store.path.resolve(storeKey)
        val lockPath = store.path.resolve("$storeKey.lock")
        val processLock = processLocks.computeIfAbsent(lockPath) { ReentrantLock() }
        try {
            processLock.lockInterruptibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.INTERRUPTED)
        }
        return try {
            try {
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    channel.lock().use {
                        when (val installed = admitInstalled(manifest, runtimeDirectory)) {
                            is InstalledRuntimeAdmission.Admitted -> installed.resolution
                            InstalledRuntimeAdmission.Missing -> install(
                                manifest,
                                source,
                                runtimeDirectory,
                            )
                            InstalledRuntimeAdmission.Rejected ->
                                SemanticRuntimeResolution.Rejected(
                                    RuntimeStoreFailure.LAYOUT_INVALID,
                                )
                        }
                    }
                }
            } catch (_: FileLockInterruptionException) {
                Thread.currentThread().interrupt()
                SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.INTERRUPTED)
            } catch (_: IOException) {
                SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.STORE_INVALID)
            } catch (_: SecurityException) {
                SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.STORE_INVALID)
            }
        } finally {
            processLock.unlock()
        }
    }

    private fun install(
        manifest: SemanticRuntimeManifest,
        source: SemanticRuntimeSource,
        runtimeDirectory: Path,
    ): SemanticRuntimeResolution {
        if (Files.exists(runtimeDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.LAYOUT_INVALID)
        }
        val storeKey = manifest.runtimeId.storeKey()
        val download = store.path.resolve("$storeKey.download.partial")
        val partial = store.path.resolve(
            "$storeKey.install.partial.${UUID.randomUUID()}",
        )
        return try {
            val acquisition = when (source) {
                SemanticRuntimeSource.Managed -> downloader.download(manifest.archive.url, download)
                is SemanticRuntimeSource.PreseededArchive -> copyPreseeded(source.archive, download)
            }
            if (acquisition is RuntimeArtifactAcquisition.Rejected) {
                return SemanticRuntimeResolution.Rejected(acquisition.failure)
            }
            if (
                Files.size(download) != manifest.archive.size.bytes ||
                sha256(download) != manifest.archive.digest.value
            ) {
                return SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.DIGEST_MISMATCH)
            }
            Files.createDirectory(partial)
            when (SafeRuntimeArchive.extract(download, partial, manifest)) {
                is RuntimeArchiveExtraction.Rejected ->
                    return SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.ARCHIVE_REJECTED)
                RuntimeArchiveExtraction.Extracted -> Unit
            }
            when (admitRuntimeLayout(partial, manifest)) {
                RuntimeLayoutAdmission.Complete -> Unit
                RuntimeLayoutAdmission.Rejected -> return SemanticRuntimeResolution.Rejected(
                    RuntimeStoreFailure.LAYOUT_INVALID,
                )
            }
            writeReceipt(partial)
            Files.move(partial, runtimeDirectory, StandardCopyOption.ATOMIC_MOVE)
            when (val installed = admitInstalled(manifest, runtimeDirectory)) {
                is InstalledRuntimeAdmission.Admitted -> installed.resolution
                InstalledRuntimeAdmission.Missing,
                InstalledRuntimeAdmission.Rejected,
                    -> SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.LAYOUT_INVALID)
            }
        } catch (_: IOException) {
            SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.ARTIFACT_UNAVAILABLE)
        } catch (_: SecurityException) {
            SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.ARTIFACT_UNAVAILABLE)
        } finally {
            Files.deleteIfExists(download)
            deletePartialTree(partial)
        }
    }
}

private fun SemanticRuntimeId.storeKey(): String = value.replace(':', '-')

private sealed interface StorePreparation {
    data object Prepared : StorePreparation
    data class Rejected(val failure: RuntimeStoreFailure) : StorePreparation
}

/**
 * Proof transition: `Path -> StorePreparation`.
 *
 * Establishes one physically canonical, non-symlinked store directory. The closed expected failure
 * is [RuntimeStoreFailure.STORE_INVALID]. Raw filesystem access remains in this adapter.
 */
private fun prepareStore(path: Path): StorePreparation = try {
    if (Files.isSymbolicLink(path)) return StorePreparation.Rejected(RuntimeStoreFailure.STORE_INVALID)
    Files.createDirectories(path)
    if (path.toRealPath() == path && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        StorePreparation.Prepared
    } else {
        StorePreparation.Rejected(RuntimeStoreFailure.STORE_INVALID)
    }
} catch (_: IOException) {
    StorePreparation.Rejected(RuntimeStoreFailure.STORE_INVALID)
} catch (_: SecurityException) {
    StorePreparation.Rejected(RuntimeStoreFailure.STORE_INVALID)
}

/**
 * Proof transition: `SemanticRuntimeManifest + Path -> InstalledRuntimeAdmission`.
 *
 * Establishes that an already-visible store entry has the exact receipt, content, layout, runtime
 * identity, and executable state. [InstalledRuntimeAdmission.Missing] permits cold acquisition and
 * [InstalledRuntimeAdmission.Rejected] closes corrupt visible state. Raw paths are retained only for
 * the process-launch boundary.
 */
private fun admitInstalled(
    manifest: SemanticRuntimeManifest,
    directory: Path,
): InstalledRuntimeAdmission {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
        return InstalledRuntimeAdmission.Missing
    }
    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        return InstalledRuntimeAdmission.Rejected
    }
    if (admitRuntimeLayout(directory, manifest) is RuntimeLayoutAdmission.Rejected) {
        return InstalledRuntimeAdmission.Rejected
    }
    if (admitRuntimeReceipt(directory) is RuntimeReceiptAdmission.Rejected) {
        return InstalledRuntimeAdmission.Rejected
    }
    val executable = directory.resolve(manifest.layout.executable.value).normalize()
    if (!executable.startsWith(directory) || !Files.isExecutable(executable)) {
        return InstalledRuntimeAdmission.Rejected
    }
    return InstalledRuntimeAdmission.Admitted(
        SemanticRuntimeResolution.Installed(
            InstalledSemanticRuntime(manifest.runtimeId, directory, executable),
        ),
    )
}

private sealed interface InstalledRuntimeAdmission {
    data object Missing : InstalledRuntimeAdmission
    data class Admitted(
        val resolution: SemanticRuntimeResolution.Installed,
    ) : InstalledRuntimeAdmission
    data object Rejected : InstalledRuntimeAdmission
}

private sealed interface HostAdmission {
    data object Compatible : HostAdmission
    data object Incompatible : HostAdmission
}

/**
 * Proof transition: `RuntimePlatform + RuntimeArchitecture + host properties -> HostAdmission`.
 *
 * [HostAdmission.Compatible] proves the exact macOS/AArch64 host required by the manifest;
 * [HostAdmission.Incompatible] is the closed expected failure. Raw system properties are extracted
 * only at this host-admission boundary.
 */
private fun admitHost(
    platform: RuntimePlatform,
    architecture: RuntimeArchitecture,
): HostAdmission = if (
    platform == RuntimePlatform.MACOS &&
    architecture == RuntimeArchitecture.AARCH64 &&
    System.getProperty("os.name").lowercase().contains("mac") &&
    System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
) {
    HostAdmission.Compatible
} else {
    HostAdmission.Incompatible
}

private fun copyPreseeded(source: Path, target: Path): RuntimeArtifactAcquisition = try {
    if (
        Files.isSymbolicLink(source) ||
        !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
    ) {
        RuntimeArtifactAcquisition.Rejected(RuntimeStoreFailure.ARTIFACT_UNAVAILABLE)
    } else {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        RuntimeArtifactAcquisition.Acquired
    }
} catch (_: IOException) {
    RuntimeArtifactAcquisition.Rejected(RuntimeStoreFailure.ARTIFACT_UNAVAILABLE)
} catch (_: SecurityException) {
    RuntimeArtifactAcquisition.Rejected(RuntimeStoreFailure.ARTIFACT_UNAVAILABLE)
}

private fun deletePartialTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return
    try {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    } catch (_: IOException) {
        // Best-effort cleanup of a never-admitted partial installation.
    } catch (_: SecurityException) {
        // Best-effort cleanup of a never-admitted partial installation.
    }
}
