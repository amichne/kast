package io.github.amichne.kast.runtime.hosted.workspace

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Passive epoch observation and transition submission run off EDT; native ports marshal their own effects. */
internal suspend fun <Value> observeWorkspaceRefresh(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    observation: suspend () -> Value,
): Value = withContext(dispatcher) { observation() }
