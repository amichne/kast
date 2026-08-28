package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot

/** Primitive-only observation boundary for one short existing-Project model read. */
internal fun interface DetachedModelObservationPort {
    fun observe(expectedRoot: CanonicalWorkspaceRoot): DetachedModelObservation
}

/**
 * Proof transition: `(AdmittedIdeProject, DetachedModelObservationPort) -> DetachedModelCapture`.
 *
 * Establishes the detached model invariants from an explicit primitive-only observation port.
 * [DetachedModelCaptureFailure] closes every expected observation or refinement failure. Raw
 * platform extraction remains confined to the port; no live value can enter the result.
 */
internal fun AdmittedIdeProject.captureDetachedModelObserved(
    observation: DetachedModelObservationPort,
): DetachedModelCapture = DetachedIdeWorkspaceModel.admit(
    canonicalRoot,
    compatibility,
    observation.observe(canonicalRoot),
)
