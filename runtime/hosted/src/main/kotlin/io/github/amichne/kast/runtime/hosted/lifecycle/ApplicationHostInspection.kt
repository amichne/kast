package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.application.ApplicationInfo
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostAutoReload
import io.github.amichne.kast.protocol.contract.IdeHostMode
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCapabilityName
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.runtime.hosted.HostedCompatibilityMetadata

/** Managed project registration belongs to the native opener, after project initialization. */
internal fun observeApplicationProjects(mode: IdeHostMode, observeBorrowed: () -> Unit) {
    when (mode) {
        IdeHostMode.DESKTOP -> observeBorrowed()
        IdeHostMode.MANAGED_HEADLESS -> Unit
    }
}

internal fun lifecycleHostModeSupported(): Boolean =
    !com.intellij.idea.AppMode.isRemoteDevHost() &&
        !com.intellij.util.PlatformUtils.isJetBrainsClient() &&
        !com.intellij.util.PlatformUtils.isGateway()

/** Application inspection carries the loaded identity and the current scoped operation support. */
internal fun inspectApplicationHost(
    identity: IdeHostRuntimeIdentity,
    projects: List<IdeProjectDescription>,
    refresh: HostBundleRefreshAdapter,
): IdeLifecycleResult.Inspected {
    return IdeLifecycleResult.Inspected(
        identity.hostLifetime,
        identity.ideaHome,
        ApplicationInfo.getInstance().build.asString(),
        projects,
        HostedCompatibilityMetadata.document,
        capabilities =
            applicationLifecycleCapabilities(identity, refresh).let { capabilities ->
                if (com.intellij.openapi.application.ApplicationManager.getApplication().isHeadlessEnvironment)
                    capabilities - IdeLifecycleCapabilityName.PRESENT
                else capabilities
            },
        runtimeIdentity = identity,
    )
}

internal fun applicationLifecycleCapabilities(
    identity: IdeHostRuntimeIdentity,
    refresh: HostBundleRefreshAdapter,
): Set<IdeLifecycleCapabilityName> {
    val capabilities = IdeLifecycleCapabilityName.entries.toSet()
    return if (identity.autoReload == HostAutoReload.ENABLED && refresh.admit(identity) is Refinement.Refined)
        capabilities
    else
        capabilities -
            setOf(
                IdeLifecycleCapabilityName.HOST_RELOAD_REFRESH,
                IdeLifecycleCapabilityName.HOST_PREPARED_RELOAD_REFRESH,
            )
}

/** One native capture supplies both application and project advertisements for this Host lifetime. */
internal fun captureApplicationAdvertisement(
    home: java.nio.file.Path,
    lifetime: String,
): io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement {
    val managed = ManagedHeadlessHost.current()
    val identity =
        IdeHostRuntimeIdentity(
            ideaHome = home.toString(),
            profile =
                java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getConfigPath())
                    .toRealPath()
                    .toString(),
            pid = ProcessHandle.current().pid(),
            processStartEpochMillis = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),
            hostLifetime = lifetime,
            buildIdentity = HostedCompatibilityMetadata.buildIdentity,
            mode =
                if (managed == null) io.github.amichne.kast.protocol.contract.IdeHostMode.DESKTOP
                else io.github.amichne.kast.protocol.contract.IdeHostMode.MANAGED_HEADLESS,
            autoReload =
                if (managed == null && System.getProperty("idea.auto.reload.plugins") == "true") HostAutoReload.ENABLED
                else HostAutoReload.DISABLED,
        )
    return io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement(
        identity,
        HostedCompatibilityMetadata.document,
    )
}
