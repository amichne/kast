package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import java.io.IOException
import java.nio.file.Path
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

    fun submit(completed: () -> Unit): HostRefreshSubmission
}

internal class HostBundleRefreshAdapter(
    private val read: () -> Refinement<HostBundleRefreshObservation, IdeLifecycleFailure> = ::readNativeHostBundle
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

private fun readNativeHostBundle(): Refinement<HostBundleRefreshObservation, IdeLifecycleFailure> =
    try {
        val home = Path.of(PathManager.getHomePath()).toRealPath()
        val profile = Path.of(PathManager.getConfigPath()).toRealPath()
        val plugins = Path.of(PathManager.getPluginsPath()).toRealPath()
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("io.github.amichne.kast.ide-hosted"))
        if (descriptor == null) Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
        else {
            val plugin = descriptor.pluginPath.toRealPath()
            val virtual = LocalFileSystem.getInstance().findFileByNioFile(plugin)
            if (virtual == null || !virtual.isValid || !virtual.isDirectory)
                Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
            else
                Refinement.Refined(
                    HostBundleRefreshObservation(
                        home,
                        profile,
                        plugins,
                        plugin,
                        NativeHostBundleRefreshRoot(plugin, virtual),
                    )
                )
        }
    } catch (_: IOException) {
        Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
    } catch (_: SecurityException) {
        Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
    }

private class NativeHostBundleRefreshRoot(override val path: Path, private val root: VirtualFile) :
    HostBundleRefreshRoot {
    override fun submit(completed: () -> Unit): HostRefreshSubmission =
        try {
            if (!root.isValid || root.toNioPath() != path || path.toRealPath() != path)
                HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
            else {
                val roots = VfsUtil.markDirty(true, true, root)
                if (roots.size != 1 || roots.single() !== root)
                    HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
                else {
                    LocalFileSystem.getInstance().refreshFiles(roots, true, true, Runnable(completed))
                    HostRefreshSubmission.Submitted
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_FAILED)
        } catch (_: IOException) {
            HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE)
        } catch (_: SecurityException) {
            HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
        }
}
