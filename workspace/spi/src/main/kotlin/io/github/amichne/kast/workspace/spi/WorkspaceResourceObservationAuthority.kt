package io.github.amichne.kast.workspace.spi

import io.github.amichne.kast.workspace.contract.WorkspaceResourceObservation

fun interface WorkspaceResourceObservationAuthority {
    /**
     * Proof transition:
     * `WorkspaceResourceObservationAuthority -> WorkspaceResourceObservation`.
     *
     * Establishes one detached heap, EDT, and external active-resource observation. Raw runtime
     * memory, liveness, transition, indexing, and operation counters remain inside the physical
     * adapter. Controller-owned initiations must not be counted by the adapter.
     */
    fun observe(): WorkspaceResourceObservation
}
