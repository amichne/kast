package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.ManagedHeadlessInstallation
import io.github.amichne.kast.distribution.managed.ManagedHeadlessLaunch
import io.github.amichne.kast.distribution.managed.ManagedHeadlessLaunchFailure
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostMode
import io.github.amichne.kast.protocol.contract.IdeLifecycleCapabilityName
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.SupportedIdeReleaseLine
import java.nio.file.Path

sealed interface IdeLifecycleDeployment {
    data class Headless(val state: Path) : IdeLifecycleDeployment
}

internal sealed interface SelectedLifecycleDeployment {
    data class Headless(val installation: ManagedHeadlessInstallation) : SelectedLifecycleDeployment
}

internal fun selectLifecycleDeployment(
    deployment: IdeLifecycleDeployment,
    userHome: Path,
    selectedHome: Path,
): Refinement<SelectedLifecycleDeployment, IdeLifecycleFailure> =
    when (deployment) {
        is IdeLifecycleDeployment.Headless -> selectManagedLifecycleDeployment(deployment.state, userHome, selectedHome)
    }

private fun selectManagedLifecycleDeployment(
    state: Path,
    home: Path,
    idea: Path,
): Refinement<SelectedLifecycleDeployment, IdeLifecycleFailure> {
    if (home != state.resolve("home")) return Refinement.Rejected(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE)
    val launch =
        when (val selected = SelectedIdeInstallation.resolve(idea)) {
            is SelectedIdeLaunch.Unavailable -> return Refinement.Rejected(IdeLifecycleFailure.SELECTED_IDE_UNAVAILABLE)
            is SelectedIdeLaunch.Resolved -> selected
        }
    return when (val admitted = ManagedHeadlessInstallation.resolve(launch, state, home)) {
        is Refinement.Rejected -> Refinement.Rejected(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE)
        is Refinement.Refined -> Refinement.Refined(SelectedLifecycleDeployment.Headless(admitted.value))
    }
}

internal fun admitLifecycleHost(
    deployment: SelectedLifecycleDeployment,
    host: IdeLifecycleResult.Inspected,
): Refinement<Unit, IdeLifecycleFailure> {
    val identity = host.runtimeIdentity
    val matches =
        when (deployment) {
            is SelectedLifecycleDeployment.Headless ->
                identity != null &&
                    managedIdentityMatches(deployment.installation, identity) &&
                    identity.hostLifetime == host.host
        }
    return if (matches) Refinement.Refined(Unit) else Refinement.Rejected(IdeLifecycleFailure.HOST_IDENTITY_MISMATCH)
}

internal fun launchManagedHeadless(
    installation: ManagedHeadlessInstallation,
    platform: IdeLaunchPlatform,
): Refinement<IdeLaunchLifetime, IdeLifecycleFailure> =
    when (val specification = ManagedHeadlessLaunch.prepare(installation, platform.executablePath())) {
        is Refinement.Rejected ->
            Refinement.Rejected(
                when (specification.failure) {
                    ManagedHeadlessLaunchFailure.METADATA_UNAVAILABLE -> IdeLifecycleFailure.LAUNCH_METADATA_UNAVAILABLE
                    ManagedHeadlessLaunchFailure.INVALID_METADATA -> IdeLifecycleFailure.LAUNCH_METADATA_INVALID
                    ManagedHeadlessLaunchFailure.INVALID_EXECUTABLE_PATH -> IdeLifecycleFailure.LAUNCH_METADATA_INVALID
                    ManagedHeadlessLaunchFailure.WRITE_FAILED -> IdeLifecycleFailure.LAUNCH_FAILED
                }
            )
        is Refinement.Refined ->
            try {
                when (val started = platform.startManaged(specification.value)) {
                    is Refinement.Rejected -> started
                    is Refinement.Refined -> Refinement.Refined(IdeLaunchLifetime.Application(started.value))
                }
            } catch (_: java.io.IOException) {
                Refinement.Rejected(IdeLifecycleFailure.LAUNCH_FAILED)
            }
    }

internal fun admitWorkspaceLifecycleHost(
    home: Path,
    host: IdeLifecycleResult.Inspected,
    command: IdeLifecycleCommand,
    deployment: SelectedLifecycleDeployment,
): Refinement<Unit, IdeLifecycleFailure> {
    when (val admission = admitLifecycleHost(deployment, host)) {
        is Refinement.Rejected -> return admission
        is Refinement.Refined -> Unit
    }
    if (host.home != home.toString()) return Refinement.Rejected(IdeLifecycleFailure.HOST_IDENTITY_MISMATCH)
    if (host.protocol != 1) return Refinement.Rejected(IdeLifecycleFailure.UNSUPPORTED_PROTOCOL)
    if (!host.capabilities.containsAll(WORKSPACE_CAPABILITIES))
        return Refinement.Rejected(IdeLifecycleFailure.CAPABILITY_MISMATCH)
    if (command is IdeLifecycleCommand.Present && IdeLifecycleCapabilityName.PRESENT !in host.capabilities)
        return Refinement.Rejected(IdeLifecycleFailure.CAPABILITY_MISMATCH)
    if (SupportedIdeReleaseLine.parse(host.build.substringAfter('-').substringBefore('.')) is Refinement.Rejected)
        return Refinement.Rejected(IdeLifecycleFailure.UNSUPPORTED_PLATFORM_LINE)
    val required =
        when (val policy = requiredHostedCompatibilityPolicy()) {
            is Refinement.Rejected -> return Refinement.Rejected(IdeLifecycleFailure.COMPATIBILITY_REJECTED)
            is Refinement.Refined -> policy.value
        }
    return if (required.admit(host.compatibility) is IdeHostCompatibilityAdmission.Rejected)
        Refinement.Rejected(IdeLifecycleFailure.COMPATIBILITY_REJECTED)
    else Refinement.Refined(Unit)
}

private val WORKSPACE_CAPABILITIES =
    setOf(
        IdeLifecycleCapabilityName.INSPECT,
        IdeLifecycleCapabilityName.OPEN,
        IdeLifecycleCapabilityName.SYNC,
        IdeLifecycleCapabilityName.CONFIGURE_SYNC,
        IdeLifecycleCapabilityName.RELEASE,
        IdeLifecycleCapabilityName.CLOSE,
        IdeLifecycleCapabilityName.STATUS,
        IdeLifecycleCapabilityName.EXACT_USER_CLOSE,
    )

internal fun managedIdentityMatches(
    installation: ManagedHeadlessInstallation,
    identity: io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity,
): Boolean =
    identity.mode == IdeHostMode.MANAGED_HEADLESS &&
        identity.autoReload == io.github.amichne.kast.protocol.contract.HostAutoReload.DISABLED &&
        identity.ideaHome == installation.launch.home &&
        identity.profile == installation.config.toString() &&
        identity.pid > 0 &&
        identity.processStartEpochMillis > 0
