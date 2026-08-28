package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.contract.ChangeIntent
import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import io.github.amichne.kast.change.recovery.PreparedAddDeclarationRecovery
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

/**
 * Permission for exactly one source insertion against one repository state.
 *
 * The private constructor makes a bare plan, observation, or source path insufficient. Authority
 * exists only after pure current-state admission and durable exact pre-write recovery evidence.
 */
class MutationAuthority private constructor(
    val planId: AddDeclarationPlanId,
    val binding: MutationPlanBinding,
    val source: SymbolDiscoveryFileIdentity.Workspace,
    val intent: ChangeIntent,
    val priorLease: SemanticReadLease,
    private val precondition: ObservedMutationPrecondition,
    private val postimage: DerivedMutationPostimage,
) {
    val expectedPostimage: WorkspaceSourceContentHash
        get() = postimage.content

    /** Closed exact preimage state leaves only at the IntelliJ source-write boundary. */
    fun preconditionAtIntellijBoundary(): MutationPreconditionAtIntellijBoundary =
        when (val preimage = precondition) {
            is ObservedMutationSource -> MutationPreconditionAtIntellijBoundary.Existing(
                preimage.text,
            )
            is ObservedAbsentMutationSource -> MutationPreconditionAtIntellijBoundary.Absent
        }

    /** Raw postimage text leaves only at the IntelliJ source-write boundary. */
    fun postimageTextAtIntellijBoundary(): String = postimage.text

    /** Planned typed transformations leave only at the IntelliJ source-write boundary. */
    fun mutationsAtIntellijBoundary(): List<SourceTextMutation> = postimage.mutations

    /** Raw exact postimage bytes leave only for physical save observation. */
    fun postimageBytesAtIntellijBoundary(): ByteArray =
        postimage.text.toByteArray(Charsets.UTF_8)

    companion object {
        /**
         * Proof transition: `(AdmittedMutation, PreparedAddDeclarationRecovery) ->
         * MutationAuthority`.
         *
         * Establishes that the one admitted write has byte-exact recovery evidence durably stored
         * before any source writer can receive it. There is no expected failure because both inputs
         * already carry their proofs. Raw source extraction is permitted only by the IntelliJ
         * source writer or its exact rollback boundary.
         */
        internal fun issue(
            admitted: AdmittedMutation,
            recovery: PreparedAddDeclarationRecovery,
        ): MutationAuthority = MutationAuthority(
            admitted.request.plan.planId,
            recovery.record.binding,
            admitted.write.source,
            admitted.request.plan.intent,
            admitted.request.workspace.readLease,
            admitted.write.preimage,
            admitted.write.postimage,
        )

        fun restore(
            plan: ChangePlan,
            record: MutationRecoveryRecord.AppliedWritesDurable,
        ): Refinement<MutationAuthority, MutationAuthorityRestorationFailure> {
            val write = plan.writes.entries.singleOrNull()
                ?: return Refinement.Rejected(
                    MutationAuthorityRestorationFailure.WRITE_SET_MISMATCH,
                )
            if (
                record.binding.value != plan.planId.value ||
                record.appliedWrites.sources.singleOrNull()?.value != write.source.path.value
            ) {
                return Refinement.Rejected(
                    MutationAuthorityRestorationFailure.RECOVERY_BINDING_MISMATCH,
                )
            }
            val plannedRecovery = record.preparation.plannedWrites.singleOrNull()
                ?: return Refinement.Rejected(
                    MutationAuthorityRestorationFailure.RECOVERY_BINDING_MISMATCH,
                )
            val precondition = when (write.precondition) {
                is io.github.amichne.kast.change.contract.PlannedSourcePrecondition.Existing ->
                    when (val captured = ObservedMutationSource.capture(
                        write.source,
                        plannedRecovery.preimage.decodeAtRecoveryBoundary(),
                        SourceWriteAccess.Writable,
                    )) {
                        is Refinement.Refined -> captured.value
                        is Refinement.Rejected -> return Refinement.Rejected(
                            MutationAuthorityRestorationFailure.PREIMAGE_INVALID,
                        )
                    }
                io.github.amichne.kast.change.contract.PlannedSourcePrecondition.Absent ->
                    return Refinement.Rejected(
                        MutationAuthorityRestorationFailure.UNSUPPORTED_PLAN,
                    )
            }
            val postimage = when (val derived = DerivedMutationPostimage.derive(
                precondition,
                write.mutations,
            )) {
                is Refinement.Refined -> derived.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    MutationAuthorityRestorationFailure.POSTIMAGE_INVALID,
                )
            }
            return Refinement.Refined(
                MutationAuthority(
                    plan.planId,
                    record.binding,
                    write.source,
                    plan.intent,
                    plan.priorLease,
                    precondition,
                    postimage,
                ),
            )
        }
    }
}

enum class MutationAuthorityRestorationFailure {
    WRITE_SET_MISMATCH,
    RECOVERY_BINDING_MISMATCH,
    PREIMAGE_INVALID,
    POSTIMAGE_INVALID,
    UNSUPPORTED_PLAN,
}

/** Raw boundary projection that cannot confuse an absent target with an empty existing file. */
sealed interface MutationPreconditionAtIntellijBoundary {
    data class Existing(
        val text: String,
    ) : MutationPreconditionAtIntellijBoundary

    data object Absent : MutationPreconditionAtIntellijBoundary
}

enum class MutationDurabilityFailure {
    RECOVERY_EVIDENCE_REJECTED,
    ALREADY_DECIDED,
}

sealed interface MutationDurabilityResult {
    data object Durable : MutationDurabilityResult

    data class Rejected(
        val failure: MutationDurabilityFailure,
    ) : MutationDurabilityResult
}

/** Applied-write evidence barrier bound internally to one exact [MutationAuthority]. */
fun interface MutationDurabilityBarrier {
    /**
     * Proof transition: `PreparedAddDeclarationRecovery -> MutationDurabilityResult`.
     *
     * Durable establishes an atomically persisted applied-write record for the exact authority
     * before physical save. [MutationDurabilityFailure] closes expected persistence or repeated
     * transition failure. Persistence handles remain inside the supplied implementation.
     */
    fun recordApplied(): MutationDurabilityResult
}

enum class SourceObservationFailure {
    DUMB_MODE,
    TARGET_NOT_FOUND,
    TARGET_NOT_KOTLIN,
    TARGET_INVALIDATED,
    DOCUMENT_UNAVAILABLE,
    SOURCE_BYTES_UNAVAILABLE,
    INVALID_SOURCE_CONTENT,
}

sealed interface SourceObservationResult {
    data class Observed(
        val source: ObservedMutationPrecondition,
    ) : SourceObservationResult

    data class Rejected(
        val failure: SourceObservationFailure,
    ) : SourceObservationResult
}

/** Physical current-source observation port; it grants no write capability. */
fun interface AddDeclarationSourceObserver {
    /**
     * Proof transition: `WorkspaceFile -> SourceObservationResult`.
     *
     * Observed carries exact current bytes and writability for the requested file.
     * [SourceObservationFailure] is the closed expected failure. Paths, documents, PSI, and bytes
     * may be extracted only inside the physical adapter.
     */
    fun observe(source: SymbolDiscoveryFileIdentity.Workspace): SourceObservationResult
}

enum class AppliedSourceWriteFailure {
    INVALID_CONTENT,
    POSTIMAGE_MISMATCH,
    CHANGED_WRITE_SET_MISMATCH,
}

/** Exact physically observed singleton postimage for one authority. */
class AppliedSourceWrite private constructor(
    internal val authority: MutationAuthority,
    val content: WorkspaceSourceContentHash,
) {
    companion object {
        /**
         * Proof transition: `(MutationAuthority, ByteArray, Set<String>) -> Refinement<
         * AppliedSourceWrite, AppliedSourceWriteFailure>`.
         *
         * Establishes that physical bytes equal the authority's exact postimage and the observed
         * changed-path set is exactly its singleton source. [AppliedSourceWriteFailure] is the
         * closed expected failure. Raw bytes and paths may enter only from the IntelliJ after-save
         * observation boundary and are retained only as typed identities.
         */
        fun observe(
            authority: MutationAuthority,
            rawContent: ByteArray,
            rawChangedPaths: Set<String>,
        ): Refinement<AppliedSourceWrite, AppliedSourceWriteFailure> {
            if (rawChangedPaths != setOf(authority.source.path.value)) {
                return Refinement.Rejected(
                    AppliedSourceWriteFailure.CHANGED_WRITE_SET_MISMATCH,
                )
            }
            val observed = when (val captured = ObservedMutationSource.capture(
                authority.source,
                rawContent,
                SourceWriteAccess.Writable,
            )) {
                is Refinement.Refined -> captured.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    AppliedSourceWriteFailure.INVALID_CONTENT,
                )
            }
            if (
                observed.content != authority.expectedPostimage ||
                observed.text != authority.postimageTextAtIntellijBoundary()
            ) {
                return Refinement.Rejected(AppliedSourceWriteFailure.POSTIMAGE_MISMATCH)
            }
            return Refinement.Refined(AppliedSourceWrite(authority, observed.content))
        }
    }
}

enum class SourceWriteFailure {
    DUMB_MODE,
    TARGET_NOT_FOUND,
    TARGET_NOT_KOTLIN,
    TARGET_READ_ONLY,
    TARGET_INVALIDATED,
    DOCUMENT_UNAVAILABLE,
    PREIMAGE_CHANGED,
    MUTATION_FAILED,
    DURABILITY_REJECTED,
    ROLLBACK_FAILED,
    SAVE_FAILED,
    OBSERVATION_FAILED,
}

sealed interface SourceWriteResult {
    data class Applied(
        val write: AppliedSourceWrite,
    ) : SourceWriteResult

    data class RejectedBeforeMutation(
        val failure: SourceWriteFailure,
    ) : SourceWriteResult

    data class RejectedAfterRollback(
        val failure: SourceWriteFailure,
    ) : SourceWriteResult

    data class RecoveryRequired(
        val failure: SourceWriteFailure,
    ) : SourceWriteResult
}

/** Sole normal source-write port; a bare plan cannot invoke it. */
fun interface AddDeclarationSourceWriter {
    /**
     * Proof transition: `(MutationAuthority, MutationDurabilityBarrier) -> SourceWriteResult`.
     *
     * Applied carries the authority's exact singleton physical postimage after the durability
     * barrier and save. [SourceWriteFailure] closes every expected platform failure. Raw IntelliJ
     * values remain inside the implementation adapter.
     */
    fun write(
        authority: MutationAuthority,
        durability: MutationDurabilityBarrier,
    ): SourceWriteResult
}

/** Exact recovery-only source effect requiring both authority and its durable applied record. */
fun interface AddDeclarationSourceRollback {
    /**
     * Proof transition: `(MutationAuthority, AppliedWritesDurable) ->
     * AddDeclarationRollbackResult`.
     *
     * RolledBack establishes the authority's exact preimage without overwriting divergent source.
     * Expected failure is closed by `AddDeclarationRollbackFailure`. Raw preimage bytes may leave
     * only inside the IntelliJ recovery boundary.
     */
    fun rollback(
        authority: MutationAuthority,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): AddDeclarationRollbackResult
}
