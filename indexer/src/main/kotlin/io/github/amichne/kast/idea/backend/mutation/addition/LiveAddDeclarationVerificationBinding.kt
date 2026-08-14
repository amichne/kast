package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.application.ApplicationInfo
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.change.contract.AddDeclarationClasspathFingerprint
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAdmission
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAuthority
import io.github.amichne.kast.change.contract.AddDeclarationProjectModelFingerprint
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.verify.intellij.IntellijAddDeclarationCompilerEnvironment
import io.github.amichne.kast.change.verify.intellij.IntellijAddDeclarationCompilerEnvironmentAuthority
import io.github.amichne.kast.change.verify.intellij.IntellijAddDeclarationCompilerEnvironmentResult
import io.github.amichne.kast.change.verify.intellij.IntellijAddDeclarationVerificationExecutor
import io.github.amichne.kast.change.verify.intellij.IntellijPublishedWorkspaceGenerationAuthority
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommand
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationLimitation
import io.github.amichne.kast.idea.WorkspaceGenerationPublication
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

/**
 * Proof transition: a live indexer backend and current workspace publication authority to
 * [IntellijAddDeclarationVerificationExecutor].
 *
 * The returned executor re-admits the installed IntelliJ product/build on every verification and
 * re-observes the current publication plus exact compiler environment. Unsupported hosts remain a
 * closed verification rejection. Raw platform build values may be extracted only at this indexer
 * runtime boundary.
 */
internal fun KastIndexerBackend.liveAddDeclarationVerificationExecutor(
    publications: WorkspaceGenerationPublication,
): IntellijAddDeclarationVerificationExecutor = addDeclarationVerificationExecutor(
    publications = publications,
    runtime = liveAddDeclarationIntellijRuntimeAuthority(),
)

/**
 * Proof transition: the installed IntelliJ application to [AddDeclarationIntellijRuntimeAuthority].
 *
 * The returned authority re-admits the live product/build for every apply or verification command.
 * Unsupported hosts remain the closed `AddDeclarationIntellijRuntimeAdmission.Unsupported` state.
 * Raw product and build strings are extracted only at this indexer runtime boundary.
 */
internal fun liveAddDeclarationIntellijRuntimeAuthority(): AddDeclarationIntellijRuntimeAuthority = {
    val build = ApplicationInfo.getInstance().build
    AddDeclarationIntellijRuntimeAdmission.admit(
        build.productCode,
        build.asStringWithoutProductCode(),
    )
}

/**
 * Proof transition: a live indexer backend, current workspace publication authority, and admitted
 * IntelliJ runtime authority to [IntellijAddDeclarationVerificationExecutor].
 *
 * The returned executor re-observes the exact published generation plus the target's current
 * Gradle owner, project model, and classpath inside every scoped verification read. Expected
 * failures remain closed by the executor's verification result. Raw IntelliJ and Gradle values
 * may be extracted only by the environment adapter below.
 */
internal fun KastIndexerBackend.addDeclarationVerificationExecutor(
    publications: WorkspaceGenerationPublication,
    runtime: AddDeclarationIntellijRuntimeAuthority,
): IntellijAddDeclarationVerificationExecutor = addDeclarationVerificationExecutor(
    publications = IntellijPublishedWorkspaceGenerationAuthority(publications::current),
    runtime = runtime,
)

/**
 * Proof transition: a live indexer backend, narrow current-publication authority, and admitted
 * IntelliJ runtime authority to [IntellijAddDeclarationVerificationExecutor].
 *
 * The returned executor re-observes exact publication and compiler-environment evidence on every
 * verification. Expected failures remain closed by the executor result. Raw IntelliJ state is
 * extracted only by the compiler-environment adapter below.
 */
internal fun KastIndexerBackend.addDeclarationVerificationExecutor(
    publications: IntellijPublishedWorkspaceGenerationAuthority,
    runtime: AddDeclarationIntellijRuntimeAuthority,
): IntellijAddDeclarationVerificationExecutor = IntellijAddDeclarationVerificationExecutor(
    project = project,
    publications = publications,
    environment = LiveAddDeclarationCompilerEnvironmentAuthority(this),
    runtime = runtime,
)

private class LiveAddDeclarationCompilerEnvironmentAuthority(
    private val backend: KastIndexerBackend,
) : IntellijAddDeclarationCompilerEnvironmentAuthority {
    /**
     * Proof transition: [AddDeclarationVerificationCommand] to
     * [IntellijAddDeclarationCompilerEnvironmentResult].
     *
     * Observed proves one current, exact Gradle source-set owner and its model/classpath
     * fingerprints for the command target. Expected owner and model failures are converted to one
     * finite [AddDeclarationVerificationLimitation]. The typed target path is extracted only at
     * the existing exact-owner IntelliJ boundary.
     */
    override fun observe(
        command: AddDeclarationVerificationCommand,
    ): IntellijAddDeclarationCompilerEnvironmentResult = try {
        backend.exactAdditionOwner(Path.of(command.plan.target.targetPath.value))
            .toVerificationEnvironment()
    } catch (failure: AdditionProofIncompleteException) {
        IntellijAddDeclarationCompilerEnvironmentResult.Rejected(
            failure.limitations.first().toVerificationLimitation(),
        )
    }
}

/**
 * Proof transition: [AdditionOwnerSnapshot] to
 * [IntellijAddDeclarationCompilerEnvironmentResult].
 *
 * Observed preserves the exact owner, project-model fingerprint, and classpath fingerprint already
 * proved by [exactAdditionOwner]. Invalid cross-contract representation is the closed compiler-
 * context failure. Legacy raw values may be extracted only at this operation-contract adapter.
 */
private fun AdditionOwnerSnapshot.toVerificationEnvironment():
    IntellijAddDeclarationCompilerEnvironmentResult {
    val verifiedOwner = when (val admitted = AddDeclarationSourceOwner.admit(
        sourceRoot = owner.sourceRoot.value,
        ideaModuleName = owner.ideaModuleName.value,
        gradleBuildRoot = owner.gradleBuildRoot.value,
        gradleProjectPath = owner.gradleProjectPath.value,
        sourceSetName = owner.sourceSetName.value,
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return unavailableCompilerEnvironment()
    }
    val verifiedModel = when (val admitted = AddDeclarationProjectModelFingerprint.parse(
        modelFingerprint.value,
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return unavailableCompilerEnvironment()
    }
    val verifiedClasspath = when (val admitted = AddDeclarationClasspathFingerprint.parse(
        classpathFingerprint.value,
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return unavailableCompilerEnvironment()
    }
    return IntellijAddDeclarationCompilerEnvironmentResult.Observed(
        IntellijAddDeclarationCompilerEnvironment.observed(
            projectModelFingerprint = verifiedModel,
            classpathFingerprint = verifiedClasspath,
            owner = verifiedOwner,
        ),
    )
}

private fun unavailableCompilerEnvironment(): IntellijAddDeclarationCompilerEnvironmentResult.Rejected =
    IntellijAddDeclarationCompilerEnvironmentResult.Rejected(
        AddDeclarationVerificationLimitation.COMPILER_CONTEXT_UNAVAILABLE,
    )

private fun AdditionProofLimitation.toVerificationLimitation(): AddDeclarationVerificationLimitation =
    when (this) {
        AdditionProofLimitation.PROJECT_MODEL_CHANGED ->
            AddDeclarationVerificationLimitation.PROJECT_MODEL_CHANGED
        AdditionProofLimitation.CLASSPATH_CHANGED -> AddDeclarationVerificationLimitation.CLASSPATH_CHANGED
        AdditionProofLimitation.SOURCE_CONTEXT_CHANGED ->
            AddDeclarationVerificationLimitation.NON_TARGET_CONTEXT_CHANGED
        AdditionProofLimitation.GENERATED_SOURCE_READ_ONLY,
        AdditionProofLimitation.SOURCE_PROVENANCE_UNKNOWN,
        AdditionProofLimitation.OUTSIDE_WORKSPACE_AUTHORITY,
        AdditionProofLimitation.HARD_EXCLUDED_MUTATION_TARGET,
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        AdditionProofLimitation.SOURCE_OWNER_AMBIGUOUS,
            -> AddDeclarationVerificationLimitation.OWNER_AND_PROVENANCE_CHANGED
        AdditionProofLimitation.TARGET_PARENT_MISSING,
        AdditionProofLimitation.TARGET_ALREADY_EXISTS,
        AdditionProofLimitation.TARGET_FILE_MISSING,
        AdditionProofLimitation.TARGET_FILE_HASH_CHANGED,
        AdditionProofLimitation.TARGET_NOT_KOTLIN_SOURCE,
            -> AddDeclarationVerificationLimitation.TARGET_CONTEXT_MISSING
        AdditionProofLimitation.POSTIMAGE_MISMATCH ->
            AddDeclarationVerificationLimitation.TARGET_POSTIMAGE_MISMATCH
        AdditionProofLimitation.PROJECT_MODEL_INCOMPLETE,
        AdditionProofLimitation.MODULE_CONTEXT_ANCHOR_UNAVAILABLE,
        AdditionProofLimitation.PROPOSED_SYNTAX_INVALID,
        AdditionProofLimitation.ZERO_DECLARATIONS,
        AdditionProofLimitation.MULTIPLE_DECLARATIONS,
        AdditionProofLimitation.UNSUPPORTED_TOP_LEVEL_DECLARATION,
        AdditionProofLimitation.COMPILER_COLLISION_SCOPE_INCOMPLETE,
        AdditionProofLimitation.DECLARATION_COLLISION,
        AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
        AdditionProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
        AdditionProofLimitation.OVERLOAD_AMBIGUOUS,
        AdditionProofLimitation.REBINDING_SCOPE_INCOMPLETE,
        AdditionProofLimitation.IMPLICIT_LOOKUP_UNACCOUNTED,
        AdditionProofLimitation.JAVA_REBINDING_UNPROVEN,
        AdditionProofLimitation.GENERATION_CHANGED,
        AdditionProofLimitation.FILE_BOTTOM_UNAVAILABLE,
        AdditionProofLimitation.NEWLINE_POLICY_UNPROVEN,
            -> AddDeclarationVerificationLimitation.COMPILER_CONTEXT_UNAVAILABLE
    }
