package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.eel.provider.LocalEelMachine
import com.intellij.workspaceModel.ide.impl.GlobalWorkspaceModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal enum class InstalledGlobalSdkSynchronization {
    SYNCHRONIZED,
    TIMED_OUT,
    PLATFORM_UNAVAILABLE,
    PLATFORM_LINKAGE_INVALID,
}

/** Await the authoritative JPS SDK state before creating any temporary project SDK identity. */
internal suspend fun synchronizeInstalledGlobalSdkModel(
    synchronize: suspend () -> Unit = {
        GlobalWorkspaceModel.getInstanceAsync(LocalEelMachine).awaitSynchronizationWithJpsModel()
    },
): InstalledGlobalSdkSynchronization = try {
    withTimeout(30_000L) { synchronize() }
    InstalledGlobalSdkSynchronization.SYNCHRONIZED
} catch (_: TimeoutCancellationException) {
    InstalledGlobalSdkSynchronization.TIMED_OUT
} catch (failure: CancellationException) {
    throw failure
} catch (_: LinkageError) {
    InstalledGlobalSdkSynchronization.PLATFORM_LINKAGE_INVALID
} catch (_: RuntimeException) {
    InstalledGlobalSdkSynchronization.PLATFORM_UNAVAILABLE
}

internal fun InstalledGlobalSdkSynchronization.observe() {
    Logger.getInstance("io.github.amichne.kast.projectSdk")
        .info("Kast project SDK initialization: stage=global-jps-synchronization outcome=$name")
}
