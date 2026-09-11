package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.ReadLimitFailure
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement

/** Explicit process-setting boundary; a hosted service retains the admitted immutable policy. */
fun readHostedConfiguration(): Refinement<ReadLimits, ReadLimitFailure> = ReadLimits.resolve(
    System.getenv(),
    System.getProperties().stringPropertyNames().filter { it.startsWith("kast.read.") }
        .associateWith(System::getProperty),
)
