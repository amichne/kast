package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.application.ApplicationInfo
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostAutoReload
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCapabilityName
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.runtime.hosted.HostedCompatibilityMetadata

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
        capabilities = applicationLifecycleCapabilities(identity, refresh),
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
