package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

internal class NativeProbeWorkflow(
    private val controls: NativeFixtureControls,
    private val source: Path,
    private val evidence: NativeChangeEvidence,
) {
    suspend fun dirtyAdmission(peer: NativeChangePeer, arguments: JsonObject, original: ByteArray) {
        val dirty = controls.probe(NativeProbeCommand.DIRTY_UNCOMMITTED, sha256(original))
        demand(dirty.documentState == NativeDocumentState.DIRTY_UNCOMMITTED, NativeFailure.PROBE_REJECTED)
        val rejected = peer.call("change_plan", arguments)
        demand(rejected.rejected(), NativeFailure.EXPECTED_REJECTION_MISSING)
        unchanged(original)
        val committed = controls.probe(NativeProbeCommand.COMMIT_DOCUMENT, sha256(original))
        demand(committed.documentState == NativeDocumentState.DIRTY_COMMITTED, NativeFailure.PROBE_REJECTED)
        val stillDirty = peer.call("change_plan", arguments)
        demand(stillDirty.rejected(), NativeFailure.EXPECTED_REJECTION_MISSING)
        unchanged(original)
        controls.probe(NativeProbeCommand.RESTORE_SAVED, sha256(original))
        evidence.record("dirty-document-refusal", NativeCaseOutcome.PASSED, committed.summary())
    }

    suspend fun divergentLoadedDocument(peer: NativeChangePeer, arguments: JsonObject, postimage: ByteArray) {
        val dirty = controls.probe(NativeProbeCommand.DIRTY_UNCOMMITTED, sha256(postimage))
        val rejected = peer.call("change_recover", arguments)
        demand(rejected.rejected(), NativeFailure.EXPECTED_REJECTION_MISSING)
        val after = controls.probe(NativeProbeCommand.OBSERVE, sha256(postimage))
        demand(after.documentSha256 == dirty.documentSha256, NativeFailure.SOURCE_CHANGED)
        unchanged(postimage)
        controls.probe(NativeProbeCommand.RESTORE_SAVED, sha256(postimage))
        evidence.record("recovery-preserves-divergent-document", NativeCaseOutcome.PASSED, after.summary())
    }

    suspend fun verifyStructure(name: String, saved: ByteArray) {
        val observed = controls.probe(NativeProbeCommand.OBSERVE, sha256(saved))
        demand(
            observed.documentState == NativeDocumentState.SAVED_COMMITTED &&
                observed.syntax == NativeSyntax.CLEAN &&
                observed.functionCount(name, "NativeChangeTarget") == 1,
            NativeFailure.PSI_STRUCTURE_REJECTED,
        )
        evidence.record("psi-structure", NativeCaseOutcome.PASSED, observed.summary())
    }

    private fun unchanged(bytes: ByteArray) =
        demand(Files.readAllBytes(source).contentEquals(bytes), NativeFailure.SOURCE_CHANGED)
}
