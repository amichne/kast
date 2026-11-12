package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.contract.IdeHostMode
import io.github.amichne.kast.protocol.contract.IdeLifecycleCapabilityName
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Path

/** Exact prior endpoint cleanup only. No workspace, semantic, launch or reload authority. */
class LegacyHostCleanupClient(userHome: Path, private val selectedHome: Path) {
    private val transport = LifecycleSocketExchange(userHome)

    fun execute(command: IdeLifecycleCommand): IdeLifecycleResult =
        when (command) {
            IdeLifecycleCommand.Inspect,
            is IdeLifecycleCommand.SettleHost,
            is IdeLifecycleCommand.ResumeHost,
            is IdeLifecycleCommand.StopHost -> manageInspectedInstallation(inspect(), command, transport::exchange)
            else -> IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST)
        }

    private fun inspect(): IdeLifecycleResult {
        val home =
            try {
                selectedHome.toRealPath()
            } catch (_: java.io.IOException) {
                return IdeLifecycleResult.Blocked(IdeLifecycleFailure.SELECTED_IDE_UNAVAILABLE)
            }
        val result = transport.exchange(home, IdeLifecycleCommand.Inspect)
        if (result is IdeLifecycleResult.Inspected) {
            val identity = result.runtimeIdentity
            if (result.home != home.toString() || result.protocol != 1)
                return IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_IDENTITY_MISMATCH)
            if (identity != null && identity.mode != IdeHostMode.DESKTOP)
                return IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_IDENTITY_MISMATCH)
        }
        return result
    }
}

internal fun manageInspectedInstallation(
    inspected: IdeLifecycleResult,
    command: IdeLifecycleCommand,
    exchange: (Path, IdeLifecycleCommand) -> IdeLifecycleResult,
): IdeLifecycleResult {
    if (inspected !is IdeLifecycleResult.Inspected) return inspected
    if (command == IdeLifecycleCommand.Inspect) return inspected
    val (host, capability) =
        when (command) {
            is IdeLifecycleCommand.SettleHost -> command.host to IdeLifecycleCapabilityName.HOST_SETTLEMENT
            is IdeLifecycleCommand.SettleHostForReload ->
                command.host to IdeLifecycleCapabilityName.HOST_PREPARED_RELOAD_REFRESH
            is IdeLifecycleCommand.ResumeHost -> command.host to IdeLifecycleCapabilityName.HOST_SETTLEMENT
            is IdeLifecycleCommand.StopHost -> command.host to IdeLifecycleCapabilityName.HOST_NORMAL_STOP
            is IdeLifecycleCommand.RefreshHostBundle ->
                command.host to IdeLifecycleCapabilityName.HOST_PREPARED_RELOAD_REFRESH
            else -> return IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST)
        }
    if (inspected.host != host) return IdeLifecycleResult.Blocked(IdeLifecycleFailure.WRONG_HOST)
    val identity =
        inspected.runtimeIdentity ?: return IdeLifecycleResult.Blocked(IdeLifecycleFailure.CAPABILITY_MISMATCH)
    if (identity.ideaHome != inspected.home || identity.hostLifetime != host)
        return IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_IDENTITY_MISMATCH)
    if (identity.pid <= 0 || identity.processStartEpochMillis <= 0)
        return IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_IDENTITY_MISMATCH)
    if (capability !in inspected.capabilities)
        return IdeLifecycleResult.Blocked(IdeLifecycleFailure.CAPABILITY_MISMATCH)
    return exchange(Path.of(inspected.home), command)
}
