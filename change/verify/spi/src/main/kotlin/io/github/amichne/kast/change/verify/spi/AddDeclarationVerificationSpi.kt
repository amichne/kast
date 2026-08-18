package io.github.amichne.kast.change.verify.spi

import io.github.amichne.kast.change.contract.AddDeclarationGeneration
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationObligation
import io.github.amichne.kast.change.contract.AddDeclarationTargetPath
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationCompilerContext
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration

sealed interface AddDeclarationVerificationCommandFailure {
    data class ResultGenerationNotNewer(
        val planned: AddDeclarationGeneration,
        val published: PublishedWorkspaceGeneration,
    ) : AddDeclarationVerificationCommandFailure
}

/**
 * Detached admission to verify one plan against its exact resulting workspace publication.
 */
@ConsistentCopyVisibility
data class AddDeclarationVerificationCommand private constructor(
    val plan: PlannedAddDeclaration,
    val publication: PublishedWorkspaceGeneration,
) {
    companion object {
        /**
         * Proof transition: plan plus published workspace generation to
         * `Refinement<AddDeclarationVerificationCommand, AddDeclarationVerificationCommandFailure>`.
         *
         * Establishes that the result publication is strictly newer than the plan's G0 generation.
         * [AddDeclarationVerificationCommandFailure] is the closed expected failure. Raw generation
         * numbers may be extracted only at this workspace-publication admission boundary.
         */
        fun admit(
            plan: PlannedAddDeclaration,
            publication: PublishedWorkspaceGeneration,
        ): Refinement<AddDeclarationVerificationCommand, AddDeclarationVerificationCommandFailure> =
            if (publication.generation.value <= plan.generation.value) {
                Refinement.Rejected(
                    AddDeclarationVerificationCommandFailure.ResultGenerationNotNewer(
                        planned = plan.generation,
                        published = publication,
                    ),
                )
            } else {
                Refinement.Refined(AddDeclarationVerificationCommand(plan, publication))
            }
    }
}

enum class AddDeclarationCompilerDiagnosticsObservation {
    CLEAR,
}

enum class AddDeclarationCollisionObservation {
    ABSENT_COMPLETE,
}

enum class AddDeclarationOutboundBindingsObservation {
    PRESERVED_COMPLETE,
}

enum class AddDeclarationExistingBindingsObservation {
    PRESERVED_NO_CANDIDATES,
}

enum class AddDeclarationObservedIdentityFailure {
    PACKAGE_MISMATCH,
    DECLARATION_NAME_MISMATCH,
    DECLARATION_KIND_MISMATCH,
    SOURCE_RANGE_INVALID,
}

@ConsistentCopyVisibility
data class AddDeclarationObservedSourceRange private constructor(
    val startOffset: Int,
    val endOffset: Int,
) {
    companion object {
        /**
         * Proof transition: raw PSI offsets to
         * `Refinement<AddDeclarationObservedSourceRange, AddDeclarationObservedIdentityFailure>`.
         *
         * Establishes a non-empty, non-negative source range. The closed expected failure is
         * [AddDeclarationObservedIdentityFailure.SOURCE_RANGE_INVALID]. Raw offsets may enter only
         * from the request-local PSI verification boundary.
         */
        fun admit(
            startOffset: Int,
            endOffset: Int,
        ): Refinement<AddDeclarationObservedSourceRange, AddDeclarationObservedIdentityFailure> =
            if (startOffset < 0 || endOffset <= startOffset) {
                Refinement.Rejected(AddDeclarationObservedIdentityFailure.SOURCE_RANGE_INVALID)
            } else {
                Refinement.Refined(AddDeclarationObservedSourceRange(startOffset, endOffset))
            }
    }
}

/**
 * Strong proof that the compiler-observed declaration has the exact planned semantic identity.
 */
@ConsistentCopyVisibility
data class AddDeclarationObservedIdentity private constructor(
    val targetPath: AddDeclarationTargetPath,
    val sourceRange: AddDeclarationObservedSourceRange,
    val packageName: String,
    val declarationName: String,
    val declarationKind: AddDeclarationKind,
    internal val expectedSemanticDelta: ExpectedAddDeclarationDelta,
) {
    companion object {
        /**
         * Proof transition: expected declaration delta plus raw compiler identity to
         * `Refinement<AddDeclarationObservedIdentity, AddDeclarationObservedIdentityFailure>`.
         *
         * Establishes exact target ownership, non-empty PSI range, package, declaration name, and
         * declaration kind equality without text rendering or signature-shape inference.
         * [AddDeclarationObservedIdentityFailure] is the closed expected failure. Raw compiler
         * names and offsets may enter only from the request-local PSI/K2 verification boundary.
         */
        fun admit(
            expected: ExpectedAddDeclarationDelta,
            expectedTargetPath: AddDeclarationTargetPath,
            observedPackageName: String,
            observedDeclarationName: String,
            observedKind: AddDeclarationKind,
            observedStartOffset: Int,
            observedEndOffset: Int,
        ): Refinement<AddDeclarationObservedIdentity, AddDeclarationObservedIdentityFailure> =
            when {
                observedPackageName != expected.packageName ->
                    Refinement.Rejected(AddDeclarationObservedIdentityFailure.PACKAGE_MISMATCH)
                observedDeclarationName != expected.declarationName ->
                    Refinement.Rejected(
                        AddDeclarationObservedIdentityFailure.DECLARATION_NAME_MISMATCH,
                    )
                observedKind != expected.declarationKind ->
                    Refinement.Rejected(
                        AddDeclarationObservedIdentityFailure.DECLARATION_KIND_MISMATCH,
                    )
                else -> when (val range = AddDeclarationObservedSourceRange.admit(
                    observedStartOffset,
                    observedEndOffset,
                )) {
                    is Refinement.Rejected -> range
                    is Refinement.Refined -> Refinement.Refined(
                        AddDeclarationObservedIdentity(
                            targetPath = expectedTargetPath,
                            sourceRange = range.value,
                            packageName = observedPackageName,
                            declarationName = observedDeclarationName,
                            declarationKind = observedKind,
                            expectedSemanticDelta = expected,
                        ),
                    )
                }
            }
    }
}

/** Exact closed verification obligations proven by a successful executor observation. */
@ConsistentCopyVisibility
data class SatisfiedAddDeclarationObligations private constructor(
    val values: List<AddDeclarationObligation>,
) {
    companion object {
        internal fun fromVerified(
            command: AddDeclarationVerificationCommand,
        ): SatisfiedAddDeclarationObligations =
            SatisfiedAddDeclarationObligations(command.plan.verification.obligations)
    }
}

/**
 * Strong result of reconciling the exact G1 compiler context and declaration against the plan.
 */
@ConsistentCopyVisibility
data class ObservedAddDeclarationVerification private constructor(
    val command: AddDeclarationVerificationCommand,
    val publication: PublishedWorkspaceGeneration,
    val identity: AddDeclarationObservedIdentity,
    val compilerContext: ExpectedAddDeclarationCompilerContext,
    val expectedSemanticDelta: ExpectedAddDeclarationDelta,
    val diagnostics: AddDeclarationCompilerDiagnosticsObservation,
    val collision: AddDeclarationCollisionObservation,
    val outboundBindings: AddDeclarationOutboundBindingsObservation,
    val existingBindings: AddDeclarationExistingBindingsObservation,
    val satisfiedObligations: SatisfiedAddDeclarationObligations,
) {
    companion object {
        /**
         * Proof transition: one scoped G1 compiler observation plus matched declaration identity to
         * `Refinement<ObservedAddDeclarationVerification,
         * ObservedAddDeclarationVerificationFailure>`.
         *
         * The executor calls this only after comparing model, classpath, exact target postimage,
         * non-target context images, complete collision/outbound scope, reference cardinality, and
         * rebinding baseline. The output retains the exact G1 publication, semantic identity, and
         * complete obligation proof. [ObservedAddDeclarationVerificationFailure] is the closed
         * failure for an identity proven against another plan. No raw compiler values escape this
         * boundary.
         */
        internal fun fromScopedCompilerRead(
            command: AddDeclarationVerificationCommand,
            compilerContext: ExpectedAddDeclarationCompilerContext,
            identity: AddDeclarationObservedIdentity,
            diagnostics: AddDeclarationCompilerDiagnosticsObservation,
            collision: AddDeclarationCollisionObservation,
            outboundBindings: AddDeclarationOutboundBindingsObservation,
            existingBindings: AddDeclarationExistingBindingsObservation,
        ): Refinement<
            ObservedAddDeclarationVerification,
            ObservedAddDeclarationVerificationFailure,
            > {
            val expected = command.plan.expectedSemanticDelta
            if (identity.expectedSemanticDelta != expected) {
                return Refinement.Rejected(
                    ObservedAddDeclarationVerificationFailure.IDENTITY_PLAN_MISMATCH,
                )
            }
            if (identity.targetPath != command.plan.target.targetPath) {
                return Refinement.Rejected(
                    ObservedAddDeclarationVerificationFailure.IDENTITY_PLAN_MISMATCH,
                )
            }
            val plannedContext = command.plan.compilerContext
            if (compilerContext.generation.value != command.publication.generation.value) {
                return Refinement.Rejected(
                    ObservedAddDeclarationVerificationFailure.RESULT_GENERATION_MISMATCH,
                )
            }
            if (compilerContext.projectModelFingerprint != plannedContext.projectModelFingerprint) {
                return Refinement.Rejected(
                    ObservedAddDeclarationVerificationFailure.PROJECT_MODEL_CHANGED,
                )
            }
            if (compilerContext.classpathFingerprint != plannedContext.classpathFingerprint) {
                return Refinement.Rejected(
                    ObservedAddDeclarationVerificationFailure.CLASSPATH_CHANGED,
                )
            }
            val plannedFiles = plannedContext.contextFiles.associateBy { it.path }
            val observedFiles = compilerContext.contextFiles.associateBy { it.path }
            if (plannedFiles.keys != observedFiles.keys) {
                return Refinement.Rejected(
                    ObservedAddDeclarationVerificationFailure.SOURCE_CONTEXT_CHANGED,
                )
            }
            val targetPath = command.plan.target.targetPath.value
            if (observedFiles[targetPath]?.sha256 != command.plan.expectedFile.postimage.sha256) {
                return Refinement.Rejected(
                    ObservedAddDeclarationVerificationFailure.TARGET_POSTIMAGE_MISMATCH,
                )
            }
            if (plannedFiles.any { (path, file) ->
                    path != targetPath && observedFiles[path] != file
                }
            ) {
                return Refinement.Rejected(
                    ObservedAddDeclarationVerificationFailure.NON_TARGET_CONTEXT_CHANGED,
                )
            }
            if (compilerContext.outboundReferenceCount != plannedContext.outboundReferenceCount) {
                return Refinement.Rejected(
                    ObservedAddDeclarationVerificationFailure.OUTBOUND_REFERENCE_COUNT_CHANGED,
                )
            }
            return Refinement.Refined(
                ObservedAddDeclarationVerification(
                    command = command,
                    publication = command.publication,
                    identity = identity,
                    compilerContext = compilerContext,
                    expectedSemanticDelta = expected,
                    diagnostics = diagnostics,
                    collision = collision,
                    outboundBindings = outboundBindings,
                    existingBindings = existingBindings,
                    satisfiedObligations = SatisfiedAddDeclarationObligations.fromVerified(
                        command,
                    ),
                ),
            )
        }
    }
}

enum class ObservedAddDeclarationVerificationFailure {
    IDENTITY_PLAN_MISMATCH,
    RESULT_GENERATION_MISMATCH,
    PROJECT_MODEL_CHANGED,
    CLASSPATH_CHANGED,
    SOURCE_CONTEXT_CHANGED,
    TARGET_POSTIMAGE_MISMATCH,
    NON_TARGET_CONTEXT_CHANGED,
    OUTBOUND_REFERENCE_COUNT_CHANGED,
}

enum class AddDeclarationVerificationLimitation {
    UNSUPPORTED_RUNTIME,
    SEMANTIC_READ_UNAVAILABLE,
    RESULT_GENERATION_MOVED,
    COMPILER_CONTEXT_UNAVAILABLE,
    COMPILER_DIAGNOSTICS_INCOMPLETE,
    COMPILER_DIAGNOSTICS_REJECTED,
    PROJECT_MODEL_CHANGED,
    CLASSPATH_CHANGED,
    TARGET_CONTEXT_MISSING,
    TARGET_POSTIMAGE_MISMATCH,
    NON_TARGET_CONTEXT_CHANGED,
    COLLISION_SCOPE_INCOMPLETE,
    OUTBOUND_SCOPE_INCOMPLETE,
    OUTBOUND_REFERENCE_COUNT_CHANGED,
    DECLARATION_NOT_FOUND,
    DECLARATION_AMBIGUOUS,
    DECLARATION_IDENTITY_MISMATCH,
    OWNER_AND_PROVENANCE_CHANGED,
    EXISTING_BINDINGS_CHANGED,
}

@ConsistentCopyVisibility
data class AddDeclarationVerificationRejection private constructor(
    val limitations: List<AddDeclarationVerificationLimitation>,
) {
    companion object {
        /**
         * Proof transition: one required limitation plus optional additional limitations to
         * `AddDeclarationVerificationRejection`.
         *
         * Establishes a non-empty, unique, declaration-ordered finite rejection set. There is no
         * expected failure because the first limitation is required by the boundary.
         */
        fun of(
            first: AddDeclarationVerificationLimitation,
            vararg additional: AddDeclarationVerificationLimitation,
        ): AddDeclarationVerificationRejection = AddDeclarationVerificationRejection(
            (listOf(first) + additional).toSet().sortedBy(AddDeclarationVerificationLimitation::ordinal),
        )
    }
}

sealed interface AddDeclarationVerificationResult {
    data class Observed(
        val verification: ObservedAddDeclarationVerification,
    ) : AddDeclarationVerificationResult

    data class Rejected(
        val command: AddDeclarationVerificationCommand,
        val rejection: AddDeclarationVerificationRejection,
    ) : AddDeclarationVerificationResult
}

abstract class AddDeclarationVerificationExecutor {
    /**
     * Proof transition: `AddDeclarationVerificationCommand ->
     * AddDeclarationVerificationResult`.
     *
     * An observed result establishes exact G0-to-G1 compiler-context reconciliation, matched
     * semantic declaration identity, bounded clear diagnostics, and every required obligation.
     * Expected failures are closed by [AddDeclarationVerificationRejection] and retain the exact
     * admitted command. Live PSI, K2, search, and diagnostic values must be consumed before return.
     */
    abstract suspend fun verify(command: AddDeclarationVerificationCommand):
        AddDeclarationVerificationResult

    /**
     * Proof transition: scoped compiler evidence to [AddDeclarationVerificationResult]. Only an
     * explicit compiler port may invoke this after one smart read proves the observations; the
     * factory also reconciles G1, model, classpath, source images, and outbound cardinality.
     */
    protected fun verified(
        command: AddDeclarationVerificationCommand,
        compilerContext: ExpectedAddDeclarationCompilerContext,
        identity: AddDeclarationObservedIdentity,
        diagnostics: AddDeclarationCompilerDiagnosticsObservation,
        collision: AddDeclarationCollisionObservation,
        outboundBindings: AddDeclarationOutboundBindingsObservation,
        existingBindings: AddDeclarationExistingBindingsObservation,
    ): AddDeclarationVerificationResult = when (val result =
        ObservedAddDeclarationVerification.fromScopedCompilerRead(
            command,
            compilerContext,
            identity,
            diagnostics,
            collision,
            outboundBindings,
            existingBindings,
        )) {
        is Refinement.Refined -> AddDeclarationVerificationResult.Observed(result.value)
        is Refinement.Rejected -> AddDeclarationVerificationResult.Rejected(
            command,
            AddDeclarationVerificationRejection.of(result.failure.toLimitation()),
        )
    }
}
