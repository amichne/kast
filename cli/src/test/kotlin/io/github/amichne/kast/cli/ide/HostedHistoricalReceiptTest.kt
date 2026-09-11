package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.cli.CliProjectionPreparation
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
import io.github.amichne.kast.protocol.contract.ChangeFilePreview
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewKind
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewSet
import io.github.amichne.kast.protocol.contract.ChangePreviewDiff
import io.github.amichne.kast.protocol.contract.ChangePreviewPath
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRecoveryDocumentState
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedHistoricalReceiptTest {
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val current = ExistingIdeDescriptor(123, UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val historical = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val changes =
        ChangeFilePreviewSet.admit(
                listOf(
                    ChangeFilePreview(
                        ChangePreviewPath.parse("src/Target.kt").refined(),
                        ChangeFilePreviewKind.UPDATE,
                        ChangePreviewDiff.parse("+fun added() = Unit").refined(),
                    )
                )
            )
            .refined()
    private val identity = "plan:${"a".repeat(64)}"
    private val assertion =
        HostedApprovalAssertion.parse("e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64)))
            .refined()

    private fun operation(kind: HostedMutationOperation): ExistingIdeOperation.ApprovedMutation {
        val request =
            when (kind) {
                HostedMutationOperation.CHANGE_APPLY ->
                    canonicalCliRequestPreparers().changeApply.prepare(ChangeApplyRequest(text(identity)))
                HostedMutationOperation.CHANGE_RECOVER ->
                    canonicalCliRequestPreparers().changeRecover.prepare(ChangeRecoverRequest(text(identity)))
            }
                as CliProjectionPreparation.Prepared
        return ExistingIdeOperation.ApprovedMutation(
            request = request.request,
            kind = kind,
            identity = HostedPlanIdentity.parse(identity).refined(),
            assertion = assertion,
        )
    }

    private fun live(path: String, owner: UUID) =
        EvidenceBasis.Live(
            LiveReadEvidence.create(
                    workspaceRoot = path,
                    host = owner,
                    epoch = 2,
                    contentView = LiveReadContentView.SAVED_PSI_COMMITTED,
                    version = 1,
                )
                .refined()
        )

    @Test
    fun `interrupted durable attempt is historical recovery evidence after owner restart`() {
        val result =
            ChangeApplyResult.RecoveryRequired(text(identity), changes, ChangeApplyRecoveryReason.ATTEMPT_INTERRUPTED)
        val raw =
            CanonicalOperationWireBindings.changeApply
                .encodeOutcome(
                    OperationOutcome.Qualified(
                        EvidenceEnvelope(CanonicalOperation.CHANGE_APPLY.id, live("/workspace", historical), result),
                        io.github.amichne.kast.protocol.contract.ChangeApplyQualification.RECOVERY_REQUIRED,
                    )
                )
                .encoded()
        assertTrue(
            ExistingIdeDocuments.response(
                raw = raw,
                root = root,
                operation = operation(HostedMutationOperation.CHANGE_APPLY),
                descriptor = current,
            ) is ExistingIdeExchange.Semantic
        )
    }

    @Test
    fun `verified receipt retains historical owner evidence after hosted owner restart`() {
        val result = ChangeApplyResult.Verified(text("receipt:original"), changes)
        val raw =
            CanonicalOperationWireBindings.changeApply
                .encodeOutcome(
                    OperationOutcome.Complete(
                        EvidenceEnvelope(CanonicalOperation.CHANGE_APPLY.id, live("/workspace", historical), result)
                    )
                )
                .encoded()
        assertTrue(
            ExistingIdeDocuments.response(
                raw = raw,
                root = root,
                operation = operation(HostedMutationOperation.CHANGE_APPLY),
                descriptor = current,
            ) is ExistingIdeExchange.Semantic
        )
    }

    @Test
    fun `historical allowance never accepts another root unverified effect or recovery`() {
        for ((basis, result) in
            listOf(
                live("/other", historical) to ChangeApplyResult.Verified(text("receipt:1"), changes),
                live("/workspace", historical) to
                    ChangeApplyResult.AppliedUnverified(
                        text(identity),
                        changes,
                        ChangeApplyUnverifiedReason.VERIFICATION_FAILED,
                    ),
                live("/workspace", historical) to
                    ChangeApplyResult.RecoveryRequired(
                        text(identity),
                        changes,
                        ChangeApplyRecoveryReason.WRITE_OUTCOME_UNKNOWN,
                    ),
                EvidenceBasis.Published(EvidenceGeneration.parse(1).refined()) to
                    ChangeApplyResult.Verified(text("receipt:1"), changes),
            )) {
            val raw =
                CanonicalOperationWireBindings.changeApply
                    .encodeOutcome(
                        OperationOutcome.Complete(EvidenceEnvelope(CanonicalOperation.CHANGE_APPLY.id, basis, result))
                    )
                    .encoded()
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                ExistingIdeDocuments.response(
                    raw = raw,
                    root = root,
                    operation = operation(HostedMutationOperation.CHANGE_APPLY),
                    descriptor = current,
                ),
            )
        }
    }

    @Test
    fun `recovery requires evidence from the current owner`() {
        val recovered =
            CanonicalOperationWireBindings.changeRecover
                .encodeOutcome(
                    OperationOutcome.Complete(
                        EvidenceEnvelope(
                            CanonicalOperation.CHANGE_RECOVER.id,
                            live("/workspace", historical),
                            ChangeRecoverResult(ChangeRecoveryDocumentState.ROLLED_BACK),
                        )
                    )
                )
                .encoded()
        assertEquals(
            ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
            ExistingIdeDocuments.response(
                raw = recovered,
                root = root,
                operation = operation(HostedMutationOperation.CHANGE_RECOVER),
                descriptor = current,
            ),
        )
    }

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun <V, F> Refinement<V, F>.refined(): V = (this as Refinement.Refined).value

    private fun WireEncoding.encoded() = (this as WireEncoding.Encoded).document.toByteArray()
}
