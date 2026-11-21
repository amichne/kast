package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

@Serializable
enum class IdeLifecycleExchangeCommand {
    INSPECT,
    OPEN,
    PRESENT,
    SYNC,
    CONFIGURE_SYNC,
    RELEASE,
    CLOSE,
    AUTHORIZED_CLOSE,
    STATUS,
    SETTLE_HOST,
    RESUME_HOST,
    STOP_HOST,
}

@Serializable
enum class IdeLifecycleExchangeStage {
    PATH_ADMISSION,
    CONNECT,
    WRITE_REQUEST,
    READ_LENGTH,
    READ_BODY,
    DECODE,
}

@Serializable
enum class IdeLifecycleExchangeCause {
    SOCKET_MISSING,
    SYMBOLIC_PATH,
    DEADLINE_EXCEEDED,
    CONNECTION_FAILED,
    INTERRUPTED,
    IO_FAILURE,
    TRUNCATED_RESPONSE,
    INVALID_FRAME,
    INVALID_RESPONSE,
}

/** Bounded facts about one lifecycle frame; a transport rejection does not establish that the IDE exited. */
@Serializable
data class IdeLifecycleExchangeFailure(
    val command: IdeLifecycleExchangeCommand,
    val stage: IdeLifecycleExchangeStage,
    val cause: IdeLifecycleExchangeCause,
    val elapsedMillis: Long,
    val limitMillis: Long,
)

fun IdeLifecycleCommand.exchangeCommand(): IdeLifecycleExchangeCommand =
    when (this) {
        IdeLifecycleCommand.Inspect -> IdeLifecycleExchangeCommand.INSPECT
        is IdeLifecycleCommand.Open -> IdeLifecycleExchangeCommand.OPEN
        is IdeLifecycleCommand.Present -> IdeLifecycleExchangeCommand.PRESENT
        is IdeLifecycleCommand.Sync -> IdeLifecycleExchangeCommand.SYNC
        is IdeLifecycleCommand.ConfigureSync -> IdeLifecycleExchangeCommand.CONFIGURE_SYNC
        is IdeLifecycleCommand.Release -> IdeLifecycleExchangeCommand.RELEASE
        is IdeLifecycleCommand.Close -> IdeLifecycleExchangeCommand.CLOSE
        is IdeLifecycleCommand.AuthorizedClose -> IdeLifecycleExchangeCommand.AUTHORIZED_CLOSE
        is IdeLifecycleCommand.Status -> IdeLifecycleExchangeCommand.STATUS
        is IdeLifecycleCommand.SettleHost -> IdeLifecycleExchangeCommand.SETTLE_HOST
        is IdeLifecycleCommand.ResumeHost -> IdeLifecycleExchangeCommand.RESUME_HOST
        is IdeLifecycleCommand.StopHost -> IdeLifecycleExchangeCommand.STOP_HOST
    }
