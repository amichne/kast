package io.github.amichne.kast.change.verify.service

import io.github.amichne.kast.change.contract.AddDeclarationApplyObservation
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationRevalidationObservation
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationSourceProvenance
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.AddDeclarationTargetWritability
import io.github.amichne.kast.change.contract.AddDeclarationUndoAvailability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.ClosedAddDeclarationApply
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.DetachedCompilerEvidence
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationCompilerContext
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AppliedUnverifiedAddDeclaration
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournalOpenResult
import io.github.amichne.kast.change.verify.spi.AddDeclarationObservedIdentity
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommand
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import org.junit.jupiter.api.assertInstanceOf

internal object AddDeclarationVerificationServiceTestSupport {
    const val ROOT = "/workspace/kast"
    const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
    val BEFORE = "package sample\n".toByteArray()
    val AFTER = "package sample\n\nfun added(): Int = 1\n".toByteArray()

    fun open(database: Path): SqliteAddDeclarationPlanJournal =
        assertInstanceOf<SqliteAddDeclarationPlanJournalOpenResult.Opened>(
            SqliteAddDeclarationPlanJournal.open(database),
        ).journal

    fun applied(
        journal: SqliteAddDeclarationPlanJournal,
        target: String = TARGET,
    ): AppliedUnverifiedAddDeclaration {
        val plan = plan(target)
        val awaiting = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(journal.store(plan)).record
        val approval = RawAddDeclarationPlanApprovalEvidence(
            planId = plan.planId.value,
            approvedBy = "agent:operator",
            evidenceSha256 = "a".repeat(64),
        ).refine().refined()
        val approved = assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
            journal.approve(
                ApproveAddDeclarationPlan.admit(plan.planId, awaiting.version, approval).refined(),
            ),
        ).record
        val revalidated = RevalidatedAddDeclaration.admit(
            plan,
            AddDeclarationRevalidationObservation.observe(
                generation = generation(7),
                target = plan.target,
                currentFile = plan.expectedFile.preimage,
                provenance = AddDeclarationSourceProvenance.AUTHORED,
                writability = AddDeclarationTargetWritability.WRITABLE,
            ).refined(),
        ).refined()
        val prepared = assertInstanceOf<PrepareAddDeclarationRecoveryResult.Prepared>(
            journal.prepareRecovery(
                PrepareAddDeclarationRecovery.admit(approved, revalidated).refined(),
            ),
        ).record
        val admitted = assertInstanceOf<BeginAddDeclarationApplyResult.Begun>(
            journal.beginApply(BeginAddDeclarationApply.admit(prepared).refined()),
        ).record
        val closure = ClosedAddDeclarationApply.prove(
            plan,
            AddDeclarationApplyObservation.observe(
                plan,
                setOf(plan.target.targetPath.value),
                plan.expectedFile.postimage,
                AddDeclarationUndoAvailability.UNAVAILABLE,
            ).refined(),
        ).refined()
        return assertInstanceOf<CompleteAddDeclarationApplyResult.Completed>(
            journal.completeApply(CompleteAddDeclarationApply.admit(admitted, closure).refined()),
        ).record
    }

    fun context(
        command: AddDeclarationVerificationCommand,
    ): ExpectedAddDeclarationCompilerContext {
        val context = ExpectedAddDeclarationCompilerContext.admitSingleSource(
            generation = command.publication.generation,
            projectModelFingerprint = "3".repeat(64),
            classpathFingerprint = "4".repeat(64),
            sourcePath = TARGET,
            sourceSha256 = hash(AFTER),
            outboundReferenceCount = 0,
        ).refined()
        return context
    }

    fun identity(
        command: AddDeclarationVerificationCommand,
    ): AddDeclarationObservedIdentity = AddDeclarationObservedIdentity.admit(
            command.plan.expectedSemanticDelta,
            expectedTargetPath = command.plan.target.targetPath,
            observedPackageName = "sample",
            observedDeclarationName = "added",
            observedKind = AddDeclarationKind.FUNCTION,
            observedStartOffset = 16,
            observedEndOffset = 36,
        ).refined()

    fun publication(value: Long): PublishedWorkspaceGeneration =
        PublishedWorkspaceGeneration(generation(value), WorkspaceStateIdentity("workspace-$value"))

    fun plan(targetPath: String = TARGET): PlannedAddDeclaration {
        val intent = RawAddDeclarationPlanRequest(
            ROOT,
            targetPath,
            hash(BEFORE),
            "fun added(): Int = 1",
        ).refine().refined()
        val owner = AddDeclarationSourceOwner.admit(
            "$ROOT/indexer/src/main/kotlin",
            "kast.indexer.main",
            ROOT,
            ":indexer",
            "main",
        ).refined()
        val target = AddDeclarationTargetCapability.admit(intent, owner).refined()
        val generation = generation(7)
        return PlannedAddDeclaration.issue(
            AddDeclarationPlanningEvidence.admit(
                intent,
                generation,
                target,
                ExpectedFileProof.admit(target, exact(BEFORE), exact(AFTER)).refined(),
                DeclaredWriteSet.admit(listOf(target.targetPath)).refined(),
                ExpectedAddDeclarationDelta.admit(
                    "sample",
                    "added",
                    AddDeclarationKind.FUNCTION,
                ).refined(),
                AddDeclarationVerificationContract.forGeneration(generation),
                ExpectedAddDeclarationCompilerContext.admitSingleSource(
                    generation,
                    "3".repeat(64),
                    "4".repeat(64),
                    targetPath,
                    hash(BEFORE),
                    0,
                ).refined(),
                DetachedCompilerEvidence.admit("{\"proof\":\"complete\"}").refined(),
            ).refined(),
        )
    }

    fun generation(value: Long): EvidenceGeneration = EvidenceGeneration.parse(value).refined()

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun exact(bytes: ByteArray): ExactFileContentProof = ExactFileContentProof.admit(
        hash(bytes),
        Base64.getEncoder().encodeToString(bytes),
    ).refined()

    fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
