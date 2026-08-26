package io.github.amichne.kast.runtime.ide.read.revalidation

import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation

/** Exact retained-Project effect capability for one fresh epoch observation. */
internal fun interface ProjectReadEpochObserver {
    /**
     * Proof transition: `ProjectReadEpochObserver -> ProjectReadEpochObservation`.
     *
     * Establishes one fresh opaque epoch from the retained admitted Project source, or returns the
     * closed observation failure. Raw IDE signal extraction remains in the workspace adapter.
     */
    fun observe(): ProjectReadEpochObservation
}
