package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.EvidenceGeneration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class AddDeclarationPlanDecodeFailure {
    MALFORMED_OR_TAMPERED,
}

object AddDeclarationPlanCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        prettyPrint = false
        classDiscriminator = "type"
    }

    fun encode(plan: PlannedAddDeclaration): String =
        json.encodeToString(PlannedAddDeclaration.serializer(), plan)

    /**
     * Proof transition:
     * String to Refinement of PlannedAddDeclaration or AddDeclarationPlanDecodeFailure.
     *
     * Establishes canonical serialized bytes, every constructor invariant, compiler-evidence
     * digest, and the SHA-256 plan identity recomputed from all G0 identity material.
     * AddDeclarationPlanDecodeFailure is the closed expected failure. Raw JSON may be extracted
     * only at a durable journal or process/request transport boundary.
     */
    fun decode(
        encoded: String,
    ): Refinement<PlannedAddDeclaration, AddDeclarationPlanDecodeFailure> {
        val decoded = runCatching {
            json.decodeFromString(PlannedAddDeclaration.serializer(), encoded)
        }.getOrNull() ?: return Refinement.Rejected(
            AddDeclarationPlanDecodeFailure.MALFORMED_OR_TAMPERED,
        )
        val validated = decoded.revalidatedOrNull()
        if (validated == null || validated != decoded || encode(validated) != encoded) {
            return Refinement.Rejected(AddDeclarationPlanDecodeFailure.MALFORMED_OR_TAMPERED)
        }
        return Refinement.Refined(validated)
    }

    internal fun encodeIdentity(material: PlanIdentityMaterial): String =
        json.encodeToString(PlanIdentityMaterial.serializer(), material)
}

private fun PlannedAddDeclaration.revalidatedOrNull(): PlannedAddDeclaration? {
    val refinedIntent = RawAddDeclarationPlanRequest(
        workspaceRoot = intent.workspaceRoot.value,
        targetPath = intent.targetPath.value,
        expectedCurrentSha256 = intent.expectedCurrentSha256.value,
        proposedDeclaration = intent.proposedDeclaration.value,
    ).refine().valueOrNull() ?: return null
    if (refinedIntent != intent) return null
    val refinedOwner = AddDeclarationSourceOwner.admit(
        sourceRoot = target.owner.sourceRoot,
        ideaModuleName = target.owner.ideaModuleName,
        gradleBuildRoot = target.owner.gradleBuildRoot,
        gradleProjectPath = target.owner.gradleProjectPath,
        sourceSetName = target.owner.sourceSetName,
    ).valueOrNull() ?: return null
    val refinedTarget = AddDeclarationTargetCapability.admit(refinedIntent, refinedOwner)
                            .valueOrNull() ?: return null
    if (refinedTarget != target) return null
    val preimage = ExactFileContentProof.admit(
        expectedFile.preimage.sha256.value,
        expectedFile.preimage.contentBase64,
    ).valueOrNull() ?: return null
    val postimage = ExactFileContentProof.admit(
        expectedFile.postimage.sha256.value,
        expectedFile.postimage.contentBase64,
    ).valueOrNull() ?: return null
    val refinedFile = ExpectedFileProof.admit(refinedTarget, preimage, postimage)
                          .valueOrNull() ?: return null
    if (refinedFile != expectedFile) return null
    val writes = DeclaredWriteSet.admit(declaredWriteSet.paths).valueOrNull() ?: return null
    if (writes != declaredWriteSet) return null
    val delta = ExpectedAddDeclarationDelta.admit(
        packageName = expectedSemanticDelta.packageName,
        declarationName = expectedSemanticDelta.declarationName,
        declarationKind = expectedSemanticDelta.declarationKind,
    ).valueOrNull() ?: return null
    if (delta != expectedSemanticDelta) return null
    val rawGeneration = EvidenceGeneration.parse(generation.value).valueOrNull() ?: return null
    val expectedVerification = AddDeclarationVerificationContract.forGeneration(rawGeneration)
    if (expectedVerification != verification) return null
    val contextFiles = compilerContext.contextFiles.map { file ->
        AddDeclarationCompilerContextFile.admit(file.path, file.sha256.value).valueOrNull()
            ?: return null
    }
    val modelFingerprint = AddDeclarationProjectModelFingerprint.parse(
        compilerContext.projectModelFingerprint.value,
    ).valueOrNull() ?: return null
    val classpathFingerprint = AddDeclarationClasspathFingerprint.parse(
        compilerContext.classpathFingerprint.value,
    ).valueOrNull() ?: return null
    val outboundCount = AddDeclarationOutboundReferenceCount.parse(
        compilerContext.outboundReferenceCount.value,
    ).valueOrNull() ?: return null
    val expectedCompilerContext = ExpectedAddDeclarationCompilerContext.admit(
        generation = rawGeneration,
        projectModelFingerprint = modelFingerprint,
        classpathFingerprint = classpathFingerprint,
        contextFiles = contextFiles,
        outboundReferenceCount = outboundCount,
    ).valueOrNull() ?: return null
    if (expectedCompilerContext != compilerContext) return null
    val detachedCompilerEvidence = DetachedCompilerEvidence.admit(compilerEvidence.canonicalJson)
                                       .valueOrNull() ?: return null
    if (detachedCompilerEvidence != compilerEvidence) return null
    val evidence = AddDeclarationPlanningEvidence.admit(
        intent = refinedIntent,
        generation = rawGeneration,
        target = refinedTarget,
        expectedFile = refinedFile,
        declaredWriteSet = writes,
        expectedSemanticDelta = delta,
        verification = expectedVerification,
        compilerContext = expectedCompilerContext,
        compilerEvidence = detachedCompilerEvidence,
    ).valueOrNull() ?: return null
    return PlannedAddDeclaration.issue(evidence)
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
