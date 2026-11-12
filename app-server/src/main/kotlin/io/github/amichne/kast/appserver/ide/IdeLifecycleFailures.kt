package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause

internal fun java.io.IOException.exchangeCause(): IdeLifecycleExchangeCause =
    when (this) {
        is java.nio.channels.ClosedByInterruptException -> IdeLifecycleExchangeCause.INTERRUPTED
        is java.net.ConnectException -> IdeLifecycleExchangeCause.CONNECTION_FAILED
        is java.io.EOFException -> IdeLifecycleExchangeCause.TRUNCATED_RESPONSE
        is java.nio.charset.CharacterCodingException -> IdeLifecycleExchangeCause.INVALID_RESPONSE
        else -> IdeLifecycleExchangeCause.IO_FAILURE
    }
