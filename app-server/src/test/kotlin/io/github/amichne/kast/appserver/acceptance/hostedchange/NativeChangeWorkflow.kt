package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NativeChangeWorkflow(
    private val session: NativeChangeSession,
    private val source: Path,
    private val evidence: NativeChangeEvidence,
    private val controls: NativeFixtureControls,
) {
    private val probes = NativeProbeWorkflow(controls, source, evidence)
    private val original = Files.readAllBytes(source)
    private lateinit var peer: NativeChangePeer
    private lateinit var initialLive: JsonObject
    private lateinit var returnedReference: String
    private lateinit var plan: JsonObject
    private lateinit var planId: String
    private lateinit var postimage: ByteArray
    private lateinit var receiptIdentity: String
    private lateinit var lostPlanIdentity: String

    suspend fun run() {
        evidence.record("provider-routing", NativeCaseOutcome.UNQUALIFIED)
        peer = session.connect()
        val first = searchClass()
        initialLive = first.objectAt("live")
        returnedReference = reference(first)
        NativeNegativeWorkflow(
                source = source,
                evidence = evidence,
                foreignRoot = session.foreignRoot,
            )
            .run(peer, returnedReference)
        evidence.record("invalid-reference", NativeCaseOutcome.UNQUALIFIED)
        val invalid = peer.call("change_plan", nativePlanArguments(returnedReference + "invalid", DECLARATION))
        demand(invalid.rejected(), NativeFailure.EXPECTED_REJECTION_MISSING)
        unchanged(original)
        evidence.record("invalid-reference", NativeCaseOutcome.PASSED)
        NativeIndexingWorkflow(controls, source, evidence)
            .run(peer, nativePlanArguments(returnedReference, DECLARATION))
        NativeModelMovementWorkflow(controls, source, evidence).run(peer)
        probes.dirtyAdmission(peer, nativePlanArguments(reference(searchClass()), DECLARATION), original)
        prepare(reference(searchClass()))
        NativeApprovalRefusals(peer, source, evidence).run(identity(), ::preview)
        editedPreimage()
        applyAndVerify()
        recoverAfterOwnerRestart()
        NativeLifecycleWorkflow(session, source, evidence, controls).run(peer, original, postimage)
        lostResponse()
        probes.undoProductionChange(peer)
        session.close()
        val replay = controls.replaceBroker(lostPlanIdentity, sha256(Files.readAllBytes(source)))
        evidence.record("broker-process-restart-no-replay", NativeCaseOutcome.PASSED, replay)
    }

    private suspend fun editedPreimage() {
        evidence.record("edited-preimage", NativeCaseOutcome.UNQUALIFIED)
        val raceImage = original + "\n// acceptance-owned external edit\n".toByteArray()
        val race =
            peer.call(
                "change_apply",
                identity(),
                beforeApproval = { shown ->
                    preview(shown)
                    Files.write(source, raceImage)
                },
            )
        demand(race.rejected(), NativeFailure.EXPECTED_REJECTION_MISSING)
        unchanged(raceImage)
        evidence.record("edited-preimage", NativeCaseOutcome.PASSED)
        Files.write(source, original) // The test owns this explicit external edit; the plugin never repairs it.
        controls.restart() // Retire queued VFS observations before planning against the restored image.
        val fresh = searchClass()
        prepare(reference(fresh))
    }

    private suspend fun applyAndVerify() {
        evidence.record("complete-workflow", NativeCaseOutcome.UNQUALIFIED)
        val applied =
            peer.call(
                "change_apply",
                identity(),
                beforeApproval = { shown ->
                    preview(shown)
                    searchClass() // Same workspace remains readable while its controller approval is pending.
                    unchanged(original)
                    evidence.record("approval-wait-releases-workspace", NativeCaseOutcome.PASSED)
                },
            )
        val receipt = verified(applied)
        receiptIdentity = receipt
        postimage = Files.readAllBytes(source)
        demand(
            !postimage.contentEquals(original) && occurrences(postimage, "fun acceptanceAdded") == 1,
            NativeFailure.DUPLICATE_DECLARATION,
        )
        probes.verifyStructure("acceptanceAdded", postimage)
        val discovered = searchFunction("acceptanceAdded", 1)
        demand(
            discovered.objectAt("live").getValue("host") == initialLive.getValue("host"),
            NativeFailure.OWNER_DID_NOT_CHANGE,
        )
        evidence.record(
            "complete-workflow",
            NativeCaseOutcome.PASSED,
            buildJsonObject {
                put("referenceSha256", sha256(returnedReference.toByteArray()))
                put("planIdentitySha256", sha256(planId.toByteArray()))
                put("receiptIdentitySha256", sha256(receipt.toByteArray()))
                put("before", initialLive)
                put("after", discovered.objectAt("live"))
                put("sourcePreimageSha256", sha256(original))
                put("sourcePostimageSha256", sha256(postimage))
            },
        )
        rejectOldEpoch(discovered)
        val retry = verified(peer.call("change_apply", identity(), beforeApproval = ::preview))
        demand(retry == receipt, NativeFailure.VERIFIED_RECEIPT_MISSING)
        unchanged(postimage)
        evidence.record("repeat-apply-reuses-receipt", NativeCaseOutcome.PASSED)
    }

    private suspend fun rejectOldEpoch(discovered: JsonObject) {
        demand(
            discovered.objectAt("live").getValue("epoch") != initialLive.getValue("epoch"),
            NativeFailure.EPOCH_DID_NOT_CHANGE,
        )
        val old = peer.call("change_plan", nativePlanArguments(returnedReference, DECLARATION))
        demand(old.rejected(), NativeFailure.EXPECTED_REJECTION_MISSING)
        unchanged(postimage)
        evidence.record(
            "old-epoch-reference",
            NativeCaseOutcome.PASSED,
            buildJsonObject {
                put("before", initialLive)
                put("after", discovered.objectAt("live"))
            },
        )
    }

    private suspend fun prepare(reference: String) {
        evidence.record("plan-has-no-source-effect", NativeCaseOutcome.UNQUALIFIED)
        val planned = peer.call("change_plan", nativePlanArguments(reference, DECLARATION))
        demand(
            !planned.rejected() && planned.document()["status"] == JsonPrimitive("complete"),
            NativeFailure.PROVIDER_REJECTED,
        )
        plan = planned.document()
        returnedReference = reference
        initialLive = plan.objectAt("live")
        planId = plan.textAt("planIdentity")
        demand(planId.matches(Regex("plan:[0-9a-f]{64}")), NativeFailure.RESULT_SHAPE_REJECTED)
        demand(
            session.trace.plannedReferenceDigests.lastOrNull() == sha256(reference.toByteArray()),
            NativeFailure.RESULT_SHAPE_REJECTED,
        )
        unchanged(original)
        val cleanRead = searchClass()
        demand(cleanRead.objectAt("live") == planned.document().objectAt("live"), NativeFailure.SOURCE_CHANGED)
        evidence.record(
            "plan-has-no-source-effect",
            NativeCaseOutcome.PASSED,
            buildJsonObject {
                put("sourceSha256", sha256(original))
                put("postPlanAdmission", "SAVED_PSI_COMMITTED")
                put("referencePassedUnchanged", true)
            },
        )
    }

    private suspend fun recoverAfterOwnerRestart() {
        val beforeRestart = searchClass()
        val unapplied =
            peer.call("change_plan", nativePlanArguments(reference(beforeRestart), "fun acceptanceRetired() = value"))
        demand(!unapplied.rejected(), NativeFailure.PROVIDER_REJECTED)
        val oldPlan = unapplied.document().textAt("planIdentity")
        controls.restart()
        val reopened = searchClass()
        demand(
            reopened.objectAt("live").getValue("host") != initialLive.getValue("host"),
            NativeFailure.OWNER_DID_NOT_CHANGE,
        )
        val stale =
            peer.call("change_plan", nativePlanArguments(reference(beforeRestart), "fun acceptanceRetired() = value"))
        demand(stale.rejected(), NativeFailure.EXPECTED_REJECTION_MISSING)
        val stalePlan = peer.call("change_apply", buildJsonObject { put("planIdentity", oldPlan) })
        demand(stalePlan.rejected(), NativeFailure.EXPECTED_REJECTION_MISSING)
        unchanged(postimage)
        evidence.record("owner-retirement-invalidates-reference-and-plan", NativeCaseOutcome.PASSED)
        probes.divergentLoadedDocument(peer, identity(), postimage)
        val historical = verified(peer.call("change_apply", identity(), beforeApproval = ::preview))
        demand(historical == receiptIdentity, NativeFailure.VERIFIED_RECEIPT_MISSING)
        unchanged(postimage)
        evidence.record("historical-receipt-after-owner-restart", NativeCaseOutcome.PASSED)
        val divergent = postimage + "\n// acceptance-owned user content\n".toByteArray()
        Files.write(source, divergent)
        val unsafe = peer.call("change_recover", identity())
        evidence.expectRecoveryRequired("recovery-preserves-divergent-content", unsafe)
        unchanged(divergent)
        evidence.record("recovery-preserves-divergent-content", NativeCaseOutcome.PASSED)
        Files.write(source, postimage)
        searchClass()
        val recovered = peer.call("change_recover", identity())
        demand(
            !recovered.rejected() &&
                recovered.document()["state"] in setOf(JsonPrimitive("rolled-back"), JsonPrimitive("prior-state")),
            NativeFailure.RECOVERY_FAILED,
        )
        unchanged(original)
        searchFunction("acceptanceAdded", 0)
        evidence.record("fresh-owner-exact-image-recovery", NativeCaseOutcome.PASSED)
    }

    private suspend fun lostResponse() {
        val found = searchClass()
        val created =
            peer.call("change_plan", nativePlanArguments(reference(found), "fun acceptanceLostResponse() = value"))
        demand(!created.rejected(), NativeFailure.PROVIDER_REJECTED)
        val id = created.document().textAt("planIdentity")
        lostPlanIdentity = id
        val arguments = buildJsonObject { put("planIdentity", id) }
        while (session.trace.completedEffects.tryReceive().isSuccess) {
            /* old completed subprocesses are not this attempt */
        }
        peer.call("change_apply", arguments, aftermath = NativeApprovalAftermath.DropResponse)
        evidence.record("provider-routing", NativeCaseOutcome.UNQUALIFIED)
        peer = session.connect()
        val recovered = peer.call("change_apply", arguments)
        demand(!recovered.rejected(), NativeFailure.PROVIDER_REJECTED)
        demand(
            recovered.document()["state"] in
                setOf(
                    JsonPrimitive("verified"),
                    JsonPrimitive("applied_unverified"),
                    JsonPrimitive("recovery_required"),
                ),
            NativeFailure.RESULT_SHAPE_REJECTED,
        )
        val after = Files.readAllBytes(source)
        demand(occurrences(after, "fun acceptanceLostResponse") <= 1, NativeFailure.DUPLICATE_DECLARATION)
        evidence.record(
            "lost-response-no-replay",
            NativeCaseOutcome.PASSED,
            buildJsonObject {
                put("retrievedState", recovered.document().getValue("state"))
                put("declarationCount", occurrences(after, "fun acceptanceLostResponse"))
            },
        )
    }

    private suspend fun searchClass() = NativeChangeRead(peer).searchClass()

    private suspend fun searchFunction(name: String, count: Int) = NativeChangeRead(peer).searchFunction(name, count)

    private fun reference(search: JsonObject): String =
        ((search["items"] as JsonArray).single() as JsonObject).textAt("symbol_ref")

    private fun identity() = buildJsonObject { put("planIdentity", planId) }

    private fun unchanged(bytes: ByteArray) =
        demand(Files.readAllBytes(source).contentEquals(bytes), NativeFailure.SOURCE_CHANGED)

    private fun verified(result: NativeToolResult): String {
        demand(
            !result.rejected() &&
                result.document()["status"] == JsonPrimitive("complete") &&
                result.document()["state"] == JsonPrimitive("verified"),
            NativeFailure.VERIFIED_RECEIPT_MISSING,
        )
        return result.document().textAt("receiptIdentity")
    }

    private fun preview(shown: NativeApprovalPreview) {
        val expected =
            (plan["changes"] as? JsonArray)?.singleOrNull() as? JsonObject
                ?: throw NativeRejected(NativeFailure.RESULT_SHAPE_REJECTED)
        val actual =
            shown.changes.singleOrNull() as? JsonObject ?: throw NativeRejected(NativeFailure.APPROVAL_PREVIEW_CHANGED)
        demand(
            actual["diff"] == expected["diff"] && actual["path"] == JsonPrimitive(source.toString()),
            NativeFailure.APPROVAL_PREVIEW_CHANGED,
        )
    }

    private fun occurrences(bytes: ByteArray, value: String): Int =
        bytes.toString(Charsets.UTF_8).windowed(value.length).count { it == value }

    private companion object {
        const val DECLARATION = "fun acceptanceAdded(): String = value"
    }
}
