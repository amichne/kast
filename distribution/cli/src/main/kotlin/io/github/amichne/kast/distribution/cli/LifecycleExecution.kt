package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.encodeToString

internal data class LifecycleExecution(
    val child: LifecycleChildExecutor = NativeLifecycleChildExecutor,
    val processes: LifecycleProcesses = NativeLifecycleProcesses,
    val observe: (LifecycleObservation) -> Unit = { System.err.println(managementJson.encodeToString(it)) },
)
