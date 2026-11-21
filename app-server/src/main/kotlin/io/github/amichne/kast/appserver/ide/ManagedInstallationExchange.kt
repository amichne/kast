package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.contract.IdeLifecycleCapabilityName
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Path

/** Management commands reach a Host only through its inspected identity and advertised capability. */
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
            is IdeLifecycleCommand.ResumeHost -> command.host to IdeLifecycleCapabilityName.HOST_SETTLEMENT
            is IdeLifecycleCommand.StopHost -> command.host to IdeLifecycleCapabilityName.HOST_NORMAL_STOP
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
