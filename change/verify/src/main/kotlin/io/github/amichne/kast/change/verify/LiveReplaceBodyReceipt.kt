package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.LiveAppliedSourceWrite
import io.github.amichne.kast.change.apply.LiveChangeEffect
import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.change.contract.LiveReplaceBodyChangePlan
import io.github.amichne.kast.change.contract.LiveReplaceBodyPlanCodec
import io.github.amichne.kast.change.recovery.AppliedAddDeclarationRecovery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Bounded opaque exact reference, issued by the query codec only after live verification. */
@JvmInline
value class BodyExactReference private constructor(val value: String) {
    companion object {
        private const val MIN_TOKEN_LENGTH = 10
        private const val MAX_TOKEN_LENGTH = 1_048_576

        fun parse(raw: String): Refinement<BodyExactReference, LiveReceiptFailure> =
            if (
                raw.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH &&
                    raw.startsWith("exact:v") &&
                    raw.none(Char::isISOControl)
            )
                Refinement.Refined(BodyExactReference(raw))
            else Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
    }
}

/** Historical body replacement proof; it does not restore a live selector or permission to write. */
class HistoricalLiveReplaceBodyReceipt
private constructor(
    override val plan: LiveReplaceBodyChangePlan,
    override val after: LiveChangeBasis,
    val postimage: WorkspaceSourceContentHash,
    val freshReference: BodyExactReference,
    val approval: HistoricalLiveApproval,
    val recovery: HistoricalLiveRecovery,
) : HistoricalLiveChangeReceipt {
    override val identity: ChangeReceiptIdentity
        get() = LiveReplaceBodyReceiptCodec.identity(this)

    internal companion object {
        @Suppress("ComplexCondition") // The resulting read must retain owner, model, epoch, and committed content.
        fun restore(
            plan: LiveReplaceBodyChangePlan,
            after: LiveChangeBasis,
            postimage: WorkspaceSourceContentHash,
            freshReference: BodyExactReference,
            approval: HistoricalLiveApproval,
            recovery: HistoricalLiveRecovery,
        ): Refinement<HistoricalLiveReplaceBodyReceipt, LiveReceiptFailure> {
            val before = plan.basis.observation
            if (
                after.reference.workspaceRoot != before.reference.workspaceRoot ||
                    after.reference.host != before.reference.host ||
                    after.reference.epoch.value < before.reference.epoch.value ||
                    after.reference.contentView != IdeReadContentView.SAVED_PSI_COMMITTED ||
                    after.reference.version != LiveSemanticReadReference.VERSION ||
                    after.model.workspaceRoot != before.model.workspaceRoot ||
                    after.model.sourceRoots != before.model.sourceRoots
            )
                return Refinement.Rejected(LiveReceiptFailure.RESULTING_BASIS_MISMATCH)
            if (postimage != plan.expectedPostimage)
                return Refinement.Rejected(LiveReceiptFailure.SOURCE_POSTIMAGE_MISMATCH)
            if (recovery.binding != plan.planId || recovery.preparedDigest == recovery.appliedDigest)
                return Refinement.Rejected(LiveReceiptFailure.RECOVERY_MISMATCH)
            return Refinement.Refined(
                HistoricalLiveReplaceBodyReceipt(plan, after, postimage, freshReference, approval, recovery)
            )
        }
    }
}

/** Only a durable write and complete post-write verification can issue this receipt. */
class VerifiedLiveReplaceBodyReceipt private constructor(val historical: HistoricalLiveReplaceBodyReceipt) {
    val identity: ChangeReceiptIdentity
        get() = historical.identity

    companion object {
        @Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
        // The receipt is issued only after all write, approval, recovery, and verification identities agree.
        fun admit(
            write: LiveAppliedSourceWrite,
            recovery: AppliedAddDeclarationRecovery,
            verification: CompleteLiveReplaceBodyVerification,
            encodedFreshReference: String,
        ): Refinement<VerifiedLiveReplaceBodyReceipt, LiveReceiptFailure> {
            val authority = write.authority
            val plan =
                authority.plan as? LiveReplaceBodyChangePlan
                    ?: return Refinement.Rejected(LiveReceiptFailure.PLAN_MISMATCH)
            if (LiveReplaceBodyPlanCodec.encode(plan) != LiveReplaceBodyPlanCodec.encode(verification.plan))
                return Refinement.Rejected(LiveReceiptFailure.PLAN_MISMATCH)
            if (write.content != plan.expectedPostimage || verification.postimage != write.content)
                return Refinement.Rejected(LiveReceiptFailure.SOURCE_POSTIMAGE_MISMATCH)
            when (val checked = validateRecoveryBinding(authority, recovery)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return checked
            }
            val approved = authority.approval
            if (
                approved.operation != LiveChangeEffect.CHANGE_APPLY ||
                    approved.planId != plan.planId ||
                    approved.owner != plan.basis.observation.reference.host ||
                    approved.root != plan.basis.observation.reference.workspaceRoot
            )
                return Refinement.Rejected(LiveReceiptFailure.APPROVAL_MISMATCH)
            if (
                verification.freshReference.file != plan.target.file ||
                    verification.freshReference.lease.workspaceRoot != plan.basis.observation.reference.workspaceRoot
            )
                return Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
            val freshReference =
                when (val parsed = BodyExactReference.parse(encodedFreshReference)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return parsed
                }
            val approval =
                when (val result = historicalApproval(approved)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            val history =
                when (val result = historicalRecovery(recovery)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            return when (
                val restored =
                    HistoricalLiveReplaceBodyReceipt.restore(
                        plan,
                        verification.resulting,
                        verification.postimage,
                        freshReference,
                        approval,
                        history,
                    )
            ) {
                is Refinement.Refined -> Refinement.Refined(VerifiedLiveReplaceBodyReceipt(restored.value))
                is Refinement.Rejected -> restored
            }
        }
    }
}

/** The encoded identity covers the exact plan, resulting read, fresh token, and durable recovery chain. */
object LiveReplaceBodyReceiptCodec {
    const val VERSION = 1
    private const val KIND = "LIVE_REPLACE_BODY_RECEIPT"
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(receipt: HistoricalLiveReplaceBodyReceipt): String =
        json.encodeToString(
            BodyReceiptDocument.serializer(),
            BodyReceiptDocument(receipt.identity.value, content(receipt)),
        )

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
    ) // Restore every persisted proof before checking canonical identity.
    fun decode(encoded: String): Refinement<HistoricalLiveReplaceBodyReceipt, LiveReceiptFailure> {
        val document =
            try {
                json.decodeFromString(BodyReceiptDocument.serializer(), encoded)
            } catch (_: SerializationException) {
                return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
            } catch (_: IllegalArgumentException) {
                return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
            }
        if (document.content.version != VERSION || document.content.kind != KIND)
            return Refinement.Rejected(LiveReceiptFailure.VERSION_UNSUPPORTED)
        val plan =
            when (val result = LiveReplaceBodyPlanCodec.decode(document.content.plan)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.PLAN_MISMATCH)
            }
        if (document.content.diagnosticScope != plan.verificationScope.file.path.value)
            return Refinement.Rejected(LiveReceiptFailure.VERIFICATION_EVIDENCE_INCOMPLETE)
        val after =
            decodeAfter(plan, document.content.after)
                ?: return Refinement.Rejected(LiveReceiptFailure.RESULTING_BASIS_MISMATCH)
        val postimage =
            (WorkspaceSourceContentHash.parse(document.content.postimage) as? Refinement.Refined)?.value
                ?: return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
        val reference =
            (BodyExactReference.parse(document.content.freshReference) as? Refinement.Refined)?.value
                ?: return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
        val approval =
            when (val result = decodeReceiptApproval(document.content.approval)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        val recovery =
            when (val result = decodeReceiptRecovery(document.content.recovery)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        val receipt =
            when (
                val result =
                    HistoricalLiveReplaceBodyReceipt.restore(
                        plan,
                        after,
                        postimage,
                        reference,
                        approval,
                        recovery,
                    )
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        return if (receipt.identity.value == document.identity && encode(receipt) == encoded)
            Refinement.Refined(receipt)
        else Refinement.Rejected(LiveReceiptFailure.IDENTITY_MISMATCH)
    }

    internal fun identity(receipt: HistoricalLiveReplaceBodyReceipt): ChangeReceiptIdentity {
        val canonical = json.encodeToString(BodyReceiptContent.serializer(), content(receipt))
        val digest =
            java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)))
        return checkNotNull(ChangeReceiptIdentity.parse("receipt:$digest"))
    }

    private fun content(receipt: HistoricalLiveReplaceBodyReceipt): BodyReceiptContent =
        BodyReceiptContent(
            version = VERSION,
            kind = KIND,
            plan = LiveReplaceBodyPlanCodec.encode(receipt.plan),
            after =
                BodyReceiptAfter(
                    owner = receipt.after.reference.host.value.toString(),
                    epoch = receipt.after.reference.epoch.value,
                    contentView = receipt.after.reference.contentView.name,
                    version = receipt.after.reference.version,
                ),
            postimage = receipt.postimage.value,
            freshReference = receipt.freshReference.value,
            diagnosticScope = receipt.plan.verificationScope.file.path.value,
            approval =
                LiveReceiptApproval(
                    receipt.approval.thread,
                    receipt.approval.turn,
                    receipt.approval.call,
                    receipt.approval.challenge.value,
                ),
            recovery =
                LiveReceiptRecovery(
                    receipt.recovery.binding.value,
                    receipt.recovery.preparedDigest.value,
                    receipt.recovery.appliedDigest.value,
                ),
        )

    private fun decodeAfter(plan: LiveReplaceBodyChangePlan, encoded: BodyReceiptAfter): LiveChangeBasis? {
        val owner =
            try {
                UUID.fromString(encoded.owner)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (owner.toString() != encoded.owner) return null
        val epoch = (IdeReadEpochRevision.parse(encoded.epoch) as? Refinement.Refined)?.value ?: return null
        val view = IdeReadContentView.entries.singleOrNull { it.name == encoded.contentView } ?: return null
        val original = plan.basis.observation
        val reference =
            LiveSemanticReadReference(
                original.reference.workspaceRoot,
                IdeReadHostLifetime.fromBoundary(owner),
                epoch,
                view,
                encoded.version,
            )
        return (LiveChangeBasis.observe(reference, original.model) as? Refinement.Refined)?.value
    }
}

@Serializable private data class BodyReceiptDocument(val identity: String, val content: BodyReceiptContent)

@Serializable
private data class BodyReceiptContent(
    val version: Int,
    val kind: String,
    val plan: String,
    val after: BodyReceiptAfter,
    val postimage: String,
    val freshReference: String,
    val diagnosticScope: String,
    val approval: LiveReceiptApproval,
    val recovery: LiveReceiptRecovery,
)

@Serializable
private data class BodyReceiptAfter(
    val owner: String,
    val epoch: Long,
    val contentView: String,
    val version: Int,
)
