package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

internal class CallbackStorageObservation : IntellijReadObservation by IntellijReadObservation.None {
    var retainedBytes: Long = 0L
        private set

    override fun measure(gauge: IntellijReadGauge, value: IntellijReadGaugeValue) {
        if (gauge == IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES) retainedBytes = value.value
    }
}
