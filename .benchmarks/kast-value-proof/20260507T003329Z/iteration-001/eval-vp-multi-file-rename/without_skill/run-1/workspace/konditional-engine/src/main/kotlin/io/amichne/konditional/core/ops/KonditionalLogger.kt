package io.amichne.konditional.core.ops

interface KonditionalLogger {
    fun debug(message: () -> String) {}
    fun info(message: () -> String) {}
    fun warn(message: () -> String, throwable: Throwable? = null) {}
    fun error(message: () -> String, throwable: Throwable? = null) {}

    data object NoOp : KonditionalLogger
}
