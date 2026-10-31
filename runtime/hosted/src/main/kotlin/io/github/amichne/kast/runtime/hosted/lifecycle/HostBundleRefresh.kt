package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

internal sealed interface HostRefreshSubmission {
    data object Submitted : HostRefreshSubmission

    data class Rejected(val reason: IdeLifecycleFailure) : HostRefreshSubmission
}

/** The SDK adapter supplies observed paths and one root. The admission owner accepts no caller-selected path. */
internal data class HostBundleRefreshObservation(
    val home: Path,
    val profile: Path,
    val plugins: Path,
    val plugin: Path,
    val root: HostBundleRefreshRoot,
)

internal interface HostBundleRefreshRoot {
    val path: Path
    val preparedMarker: HostRefreshMarker

    fun submit(completed: () -> Unit): HostRefreshSubmission

    fun close() = Unit
}

internal class HostBundleRefreshAdapter(
    parent: Disposable? = null,
    private val read: () -> Refinement<HostBundleRefreshObservation, IdeLifecycleFailure> = {
        if (parent == null) Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
        else readNativeHostBundle(parent)
    },
) {
    fun admit(identity: IdeHostRuntimeIdentity): Refinement<HostBundleRefreshRoot, IdeLifecycleFailure> {
        val observed =
            when (val captured = read()) {
                is Refinement.Refined -> captured.value
                is Refinement.Rejected -> return captured
            }
        val home = Path.of(identity.ideaHome)
        val profile = Path.of(identity.profile)
        val plugins = profile.resolve("plugins")
        val plugin = plugins.resolve("kast-ide-hosted")
        val expected = HostBundleRefreshObservation(home, profile, plugins, plugin, observed.root)
        return if (observed == expected && observed.root.path == plugin) Refinement.Refined(observed.root)
        else Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
    }
}

private fun readNativeHostBundle(parent: Disposable): Refinement<HostBundleRefreshObservation, IdeLifecycleFailure> =
    try {
        val home = Path.of(PathManager.getHomePath()).toRealPath()
        val profile = Path.of(PathManager.getConfigPath()).toRealPath()
        val plugins = Path.of(PathManager.getPluginsPath()).toRealPath()
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("io.github.amichne.kast.ide-hosted"))
        if (descriptor == null) Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
        else {
            val plugin = descriptor.pluginPath.toRealPath()
            nativeBundleObservation(home, profile, plugins, plugin, parent)
        }
    } catch (_: IOException) {
        Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
    } catch (_: SecurityException) {
        Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
    }

private fun nativeBundleObservation(
    home: Path,
    profile: Path,
    plugins: Path,
    plugin: Path,
    parent: Disposable,
): Refinement<HostBundleRefreshObservation, IdeLifecycleFailure> {
    val virtual = LocalFileSystem.getInstance().findFileByNioFile(plugin)
    if (virtual == null || !virtual.isValid || !virtual.isDirectory)
        return Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
    val markerPath = plugin.resolve("lib/kast-ide-hosted.jar")
    val marker =
        virtual.findFileByRelativePath("lib/kast-ide-hosted.jar")
            ?: return Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
    if (!ownedMarker(marker, markerPath)) return Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
    val snapshot =
        when (val captured = HostRefreshMarker.admit(markerSnapshot(markerPath, marker.timeStamp, marker.length))) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> return captured
        }
    return Refinement.Refined(
        HostBundleRefreshObservation(
            home,
            profile,
            plugins,
            plugin,
            NativeHostBundleRefreshRoot(plugin, marker, snapshot, parent),
        )
    )
}

private fun ownedMarker(marker: VirtualFile, path: Path): Boolean {
    if (!marker.isValid || marker.isDirectory || marker.toNioPath() != path) return false
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath() == path
}

internal class HostRefreshMarker private constructor(val snapshot: HostRefreshMarkerSnapshot) {
    companion object {
        fun admit(snapshot: HostRefreshMarkerSnapshot): Refinement<HostRefreshMarker, IdeLifecycleFailure> {
            if (snapshot.cachedTimestampMillis < 0 || snapshot.physicalTimestampMillis < 0)
                return Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
            if (snapshot.cachedLength < 0 || snapshot.physicalLength < 0)
                return Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
            return Refinement.Refined(HostRefreshMarker(snapshot))
        }
    }
}

internal fun admitHostRefreshSignal(
    snapshot: HostRefreshMarkerSnapshot
): Refinement<HostRefreshMarker, IdeLifecycleFailure> {
    val admitted = HostRefreshMarker.admit(snapshot)
    if (admitted is Refinement.Rejected) return admitted
    return if (snapshot.cachedTimestampMillis == snapshot.physicalTimestampMillis)
        Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_MARKER_UNSIGNALED)
    else admitted
}

private fun markerSnapshot(path: Path, cachedTimestamp: Long, cachedLength: Long) =
    HostRefreshMarkerSnapshot(
        cachedTimestamp,
        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(),
        cachedLength,
        Files.size(path),
    )

private class NativeHostBundleRefreshRoot(
    override val path: Path,
    private val marker: VirtualFile,
    override val preparedMarker: HostRefreshMarker,
    private val parent: Disposable,
) : HostBundleRefreshRoot {
    private val connection = AtomicReference<com.intellij.util.messages.MessageBusConnection?>()

    override fun close() {
        connection.getAndSet(null)?.disconnect()
    }

    private fun completeRefresh(path: Path, before: AtomicInteger, after: AtomicInteger, completed: () -> Unit) {
        try {
            val captured = HostRefreshMarker.admit(markerSnapshot(path, marker.timeStamp, marker.length))
            val outcome =
                when (captured) {
                    is Refinement.Refined ->
                        HostRefreshOutcome.ContentEvents(before.get(), after.get(), captured.value.snapshot)
                    is Refinement.Rejected -> HostRefreshOutcome.Rejected(captured.failure)
                }
            recordHostRefresh(HostRefreshObservation(HostRefreshStage.CONTENT_EVENTS, outcome))
        } catch (_: IOException) {
            recordHostRefresh(
                HostRefreshObservation(
                    HostRefreshStage.CONTENT_EVENTS,
                    HostRefreshOutcome.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE),
                )
            )
        } catch (_: IllegalStateException) {
            recordHostRefresh(
                HostRefreshObservation(
                    HostRefreshStage.CONTENT_EVENTS,
                    HostRefreshOutcome.Rejected(IdeLifecycleFailure.HOST_REFRESH_FAILED),
                )
            )
        } catch (_: SecurityException) {
            recordHostRefresh(
                HostRefreshObservation(
                    HostRefreshStage.CONTENT_EVENTS,
                    HostRefreshOutcome.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH),
                )
            )
        } finally {
            close()
            completed()
        }
    }

    override fun submit(completed: () -> Unit): HostRefreshSubmission {
        return try {
            val markerPath = path.resolve("lib/kast-ide-hosted.jar")
            val signal = admitRefreshMarker(markerPath)
            if (signal is Refinement.Rejected) return HostRefreshSubmission.Rejected(signal.failure)
            val events =
                when (val captured = captureEvents(markerPath)) {
                    is Refinement.Refined -> captured.value
                    is Refinement.Rejected -> return HostRefreshSubmission.Rejected(captured.failure)
                }
            val roots = VfsUtil.markDirty(true, true, marker)
            if (roots.size != 1 || roots.single() !== marker) {
                close()
                return HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
            }
            LocalFileSystem.getInstance()
                .refreshFiles(
                    roots,
                    true,
                    true,
                    Runnable {
                        completeRefresh(markerPath, events.before, events.after, completed)
                    },
                )
            HostRefreshSubmission.Submitted
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (_: IllegalStateException) {
            close()
            HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_FAILED)
        } catch (_: IOException) {
            close()
            HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
        } catch (_: SecurityException) {
            close()
            HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
        }
    }

    private fun admitRefreshMarker(path: Path): Refinement<HostRefreshMarker, IdeLifecycleFailure> {
        if (!ownedMarker(marker, path)) return Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
        val snapshot =
            markerSnapshot(path, preparedMarker.snapshot.cachedTimestampMillis, preparedMarker.snapshot.cachedLength)
        recordHostRefresh(
            HostRefreshObservation(
                HostRefreshStage.MARKER_COMPARISON,
                HostRefreshOutcome.MarkerCompared(snapshot),
            )
        )
        return admitHostRefreshSignal(snapshot)
    }

    private fun captureEvents(path: Path): Refinement<MarkerEvents, IdeLifecycleFailure> {
        val events = MarkerEvents(AtomicInteger(), AtomicInteger())
        val bus = ApplicationManager.getApplication().messageBus.connect(parent)
        if (!connection.compareAndSet(null, bus)) {
            bus.disconnect()
            return Refinement.Rejected(IdeLifecycleFailure.REQUEST_CONFLICT)
        }
        bus.subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun before(changes: List<VFileEvent>) {
                    countMarkerEvents(changes, path, events.before)
                }

                override fun after(changes: List<VFileEvent>) {
                    countMarkerEvents(changes, path, events.after)
                }
            },
        )
        return Refinement.Refined(events)
    }
}

private data class MarkerEvents(val before: AtomicInteger, val after: AtomicInteger)

private const val MAXIMUM_MARKER_EVENTS = 4096

private fun countMarkerEvents(events: List<VFileEvent>, path: Path, count: AtomicInteger) {
    for (event in events) {
        if (event is VFileContentChangeEvent && event.isFromRefresh && event.path == path.toString())
            count.updateAndGet { value -> minOf(value + 1, MAXIMUM_MARKER_EVENTS) }
    }
}
