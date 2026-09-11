package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NativeProbeWorkflow(
    private val controls: NativeFixtureControls,
    private val source: Path,
    private val evidence: NativeChangeEvidence,
) {
    suspend fun dirtyAdmission(peer: NativeChangePeer, arguments: JsonObject, original: ByteArray) {
        evidence.record("dirty-document-refusal", NativeCaseOutcome.UNQUALIFIED)
        val dirty = controls.probe(NativeProbeCommand.DIRTY_UNCOMMITTED, sha256(original))
        demand(dirty.documentState == NativeDocumentState.DIRTY_UNCOMMITTED, NativeFailure.PROBE_REJECTED)
        val rejected = peer.call("change_plan", arguments)
        evidence.expectRejection("dirty-document-refusal", NativeExpectedRejection.WORKSPACE_NOT_READY, rejected)
        unchanged(original)
        val committed = controls.probe(NativeProbeCommand.COMMIT_DOCUMENT, sha256(original))
        demand(committed.documentState == NativeDocumentState.DIRTY_COMMITTED, NativeFailure.PROBE_REJECTED)
        val stillDirty = peer.call("change_plan", arguments)
        evidence.expectRejection("dirty-document-refusal", NativeExpectedRejection.WORKSPACE_NOT_READY, stillDirty)
        unchanged(original)
        controls.probe(NativeProbeCommand.RESTORE_SAVED, sha256(original))
        evidence.record("dirty-document-refusal", NativeCaseOutcome.PASSED, committed.summary())
    }

    suspend fun divergentLoadedDocument(peer: NativeChangePeer, arguments: JsonObject, postimage: ByteArray) {
        evidence.record("recovery-preserves-divergent-document", NativeCaseOutcome.UNQUALIFIED)
        val dirty = controls.probe(NativeProbeCommand.DIRTY_UNCOMMITTED, sha256(postimage))
        val rejected = peer.call("change_recover", arguments)
        evidence.expectRecoveryRequired("recovery-preserves-divergent-document", rejected)
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

    suspend fun undoProductionChange(peer: NativeChangePeer) {
        evidence.record("production-undo", NativeCaseOutcome.UNQUALIFIED)
        val before = Files.readAllBytes(source)
        val read = NativeChangeRead(peer)
        val found = read.searchClass()
        val reference = ((found["items"] as JsonArray).single() as JsonObject).textAt("symbol_ref")
        val created = peer.call("change_plan", nativePlanArguments(reference, "fun acceptanceUndo() = value"))
        demand(!created.rejected(), NativeFailure.PROVIDER_REJECTED)
        val arguments = buildJsonObject { put("planIdentity", created.document().textAt("planIdentity")) }
        val applied = peer.call("change_apply", arguments)
        demand(
            !applied.rejected() && applied.document()["state"] == JsonPrimitive("verified"),
            NativeFailure.VERIFIED_RECEIPT_MISSING,
        )
        val after = Files.readAllBytes(source)
        verifyStructure("acceptanceUndo", after)
        val undone = controls.probe(NativeProbeCommand.UNDO_PRODUCTION_CHANGE, sha256(before), sha256(after))
        unchanged(before)
        demand(
            undone.documentState == NativeDocumentState.SAVED_COMMITTED &&
                undone.syntax == NativeSyntax.CLEAN &&
                undone.functionCount("acceptanceUndo", "NativeChangeTarget") == 0,
            NativeFailure.UNDO_REJECTED,
        )
        read.searchFunction("acceptanceUndo", 0)
        evidence.record("production-undo", NativeCaseOutcome.PASSED, undone.summary())
    }

    private fun unchanged(bytes: ByteArray) =
        demand(Files.readAllBytes(source).contentEquals(bytes), NativeFailure.SOURCE_CHANGED)
}
