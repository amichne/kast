package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.IdeLaunchFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure

internal fun IdeLaunchFailure.lifecycleFailure(): IdeLifecycleFailure =
    when (this) {
        IdeLaunchFailure.METADATA_UNAVAILABLE -> IdeLifecycleFailure.LAUNCH_METADATA_UNAVAILABLE
        IdeLaunchFailure.INVALID_METADATA,
        IdeLaunchFailure.SAVED_SELECTION_REJECTED -> IdeLifecycleFailure.LAUNCH_METADATA_INVALID
        IdeLaunchFailure.UNSUPPORTED_PLATFORM_LINE -> IdeLifecycleFailure.UNSUPPORTED_PLATFORM_LINE
        IdeLaunchFailure.MISSING_LAUNCHER -> IdeLifecycleFailure.LAUNCHER_MISSING
        IdeLaunchFailure.AMBIGUOUS_LAUNCHER -> IdeLifecycleFailure.LAUNCHER_AMBIGUOUS
        IdeLaunchFailure.INVALID_LAUNCHER -> IdeLifecycleFailure.LAUNCHER_INVALID
        IdeLaunchFailure.EXECUTABLE_UNAVAILABLE -> IdeLifecycleFailure.LAUNCH_EXECUTABLE_UNAVAILABLE
    }

internal fun java.io.IOException.exchangeCause(): IdeLifecycleExchangeCause =
    when (this) {
        is java.nio.channels.ClosedByInterruptException -> IdeLifecycleExchangeCause.INTERRUPTED
        is java.net.ConnectException -> IdeLifecycleExchangeCause.CONNECTION_FAILED
        is java.io.EOFException -> IdeLifecycleExchangeCause.TRUNCATED_RESPONSE
        is java.nio.charset.CharacterCodingException -> IdeLifecycleExchangeCause.INVALID_RESPONSE
        else -> IdeLifecycleExchangeCause.IO_FAILURE
    }
