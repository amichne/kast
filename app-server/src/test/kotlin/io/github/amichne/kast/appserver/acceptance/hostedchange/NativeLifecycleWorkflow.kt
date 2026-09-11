package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class NativeLifecycleWorkflow(
    private val session: NativeChangeSession,
    private val source: Path,
    private val evidence: NativeChangeEvidence,
    private val controls: NativeFixtureControls,
) {
    suspend fun run(peer: NativeChangePeer, preimage: ByteArray, postimage: ByteArray) {
        concurrentSamePlan(peer, preimage, postimage)
        postSaveInterruption(peer, preimage, postimage)
        unloadWithPendingApproval(peer)
        controls.restart()
    }

    private suspend fun concurrentSamePlan(peer: NativeChangePeer, preimage: ByteArray, postimage: ByteArray) {
        evidence.record("concurrent-same-plan-single-effect", NativeCaseOutcome.UNQUALIFIED)
        val arguments = plan(peer)
        val second = session.connect()
        val firstReady = CompletableDeferred<Unit>()
        val secondReady = CompletableDeferred<Unit>()
        val receipts = coroutineScope {
            val first = async {
                peer.call(
                    "change_apply",
                    arguments,
                    beforeApproval = {
                        firstReady.complete(Unit)
                        secondReady.await()
                    },
                )
            }
            val other = async {
                second.call(
                    "change_apply",
                    arguments,
                    beforeApproval = {
                        secondReady.complete(Unit)
                        firstReady.await()
                    },
                )
            }
            listOf(first.await(), other.await())
        }
        evidence.concurrentResults(receipts)
        demand(
            receipts.all { !it.rejected() && it.document()["state"] == JsonPrimitive("verified") },
            NativeFailure.VERIFIED_RECEIPT_MISSING,
        )
        demand(
            receipts.map { it.document().textAt("receiptIdentity") }.distinct().size == 1,
            NativeFailure.DUPLICATE_DECLARATION,
        )
        unchanged(postimage)
        evidence.record("concurrent-same-plan-single-effect", NativeCaseOutcome.PASSED)
        recover(peer, arguments, preimage)
        second.session.detach()
    }

    private suspend fun postSaveInterruption(peer: NativeChangePeer, preimage: ByteArray, postimage: ByteArray) {
        val arguments = plan(peer)
        val before = sha256(preimage)
        val after = sha256(postimage)
        val barrier = controls.armSaveBarrier(before, after)
        peer.call(
            "change_apply",
            arguments,
            aftermath = NativeApprovalAftermath.Interrupt { controls.interruptAfterSave(before, after, barrier) },
        )
        unchanged(postimage)
        val retry = peer.call("change_apply", arguments)
        demand(
            !retry.rejected() &&
                retry.document()["state"] in
                    setOf(
                        JsonPrimitive("verified"),
                        JsonPrimitive("applied_unverified"),
                        JsonPrimitive("recovery_required"),
                    ),
            NativeFailure.RESULT_SHAPE_REJECTED,
        )
        unchanged(postimage)
        evidence.record(
            "post-save-interruption-no-replay",
            NativeCaseOutcome.PASSED,
            buildJsonObject {
                put("sourcePreimageSha256", before)
                put("sourcePostimageSha256", after)
                put("retrievedState", retry.document().getValue("state"))
            },
        )
        recover(peer, arguments, preimage)
    }

    suspend fun unloadWithPendingApproval(peer: NativeChangePeer) {
        val before = Files.readAllBytes(source)
        val found = NativeChangeRead(peer).searchClass()
        val reference = (found["items"] as JsonArray).single().jsonObject.textAt("symbol_ref")
        val planned = peer.call("change_plan", nativePlanArguments(reference, "fun acceptanceUnloaded() = value"))
        demand(!planned.rejected(), NativeFailure.PROVIDER_REJECTED)
        val arguments = buildJsonObject { put("planIdentity", planned.document().textAt("planIdentity")) }
        val rejected =
            peer.call("change_apply", arguments, beforeApproval = { controls.unloadProduction(sha256(before)) })
        demand(rejected.rejected(), NativeFailure.EXPECTED_REJECTION_MISSING)
        unchanged(before)
        evidence.record("plugin-unload-retires-pending-approval", NativeCaseOutcome.PASSED)
    }

    private suspend fun plan(peer: NativeChangePeer): JsonObject {
        val found = NativeChangeRead(peer).searchClass()
        val reference = (found["items"] as JsonArray).single().jsonObject.textAt("symbol_ref")
        val planned = peer.call("change_plan", nativePlanArguments(reference, "fun acceptanceAdded(): String = value"))
        demand(!planned.rejected(), NativeFailure.PROVIDER_REJECTED)
        return buildJsonObject { put("planIdentity", planned.document().textAt("planIdentity")) }
    }

    private suspend fun recover(peer: NativeChangePeer, arguments: JsonObject, preimage: ByteArray) {
        val recovered = peer.call("change_recover", arguments)
        demand(
            !recovered.rejected() &&
                recovered.document()["state"] in
                    setOf(
                        JsonPrimitive("rolled-back"),
                        JsonPrimitive("prior-state"),
                    ),
            NativeFailure.RECOVERY_FAILED,
        )
        unchanged(preimage)
        NativeChangeRead(peer).searchFunction("acceptanceAdded", 0)
    }

    private fun unchanged(bytes: ByteArray) =
        demand(Files.readAllBytes(source).contentEquals(bytes), NativeFailure.SOURCE_CHANGED)
}
