package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class AddDeclarationRevalidationContractTest {
    @Test
    fun `exact current observation produces revalidation and recovery material`() {
        val plan = plan()

        val revalidated = RevalidatedAddDeclaration.admit(
            plan = plan,
            observation = observation(plan),
        ).refined()

        assertEquals(plan, revalidated.plan)
        assertEquals(plan.generation, revalidated.generation)
        assertEquals(plan.target, revalidated.target)
        assertEquals(plan.planId, revalidated.recovery.planId)
        assertEquals(plan.target.targetPath, revalidated.recovery.targetPath)
        assertEquals(plan.expectedFile.preimage, revalidated.recovery.beforeImage)
    }

    @Test
    fun `every stale or unsafe predicate is a finite rejection before mutation begins`() {
        val plan = plan()
        val cases = listOf(
            observation(plan, generation = 8L) to
                AddDeclarationRevalidationFailure.GENERATION_CHANGED,
            observation(plan, targetPath = ALTERNATE_TARGET) to
                AddDeclarationRevalidationFailure.TARGET_IDENTITY_CHANGED,
            observation(plan, content = "package sample\n// changed\n".toByteArray()) to
                AddDeclarationRevalidationFailure.TARGET_CONTENT_CHANGED,
            observation(plan, owner = owner(ideaModuleName = "kast.other.main")) to
                AddDeclarationRevalidationFailure.OWNER_OR_SCOPE_CHANGED,
            observation(plan, provenance = AddDeclarationSourceProvenance.GENERATED) to
                AddDeclarationRevalidationFailure.PROVENANCE_CHANGED,
            observation(plan, writability = AddDeclarationTargetWritability.READ_ONLY) to
                AddDeclarationRevalidationFailure.TARGET_READ_ONLY,
        )

        cases.forEach { (current, expectedFailure) ->
            val rejection = RevalidatedAddDeclaration.admit(plan, current).rejected()

            assertEquals(expectedFailure, rejection.failure)
            assertEquals(AddDeclarationMutationProgress.NOT_BEGUN, rejection.mutationProgress)
        }
    }

    private fun observation(
        plan: PlannedAddDeclaration,
        generation: Long = 7L,
        targetPath: String = TARGET,
        content: ByteArray = PREIMAGE,
        owner: AddDeclarationSourceOwner = owner(),
        provenance: AddDeclarationSourceProvenance = AddDeclarationSourceProvenance.AUTHORED,
        writability: AddDeclarationTargetWritability = AddDeclarationTargetWritability.WRITABLE,
    ): AddDeclarationRevalidationObservation {
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = targetPath,
            expectedCurrentSha256 = hash(content),
            proposedDeclaration = plan.intent.proposedDeclaration.value,
        ).refine().refined()
        val target = AddDeclarationTargetCapability.admit(intent, owner).refined()
        val currentFile = ExactFileContentProof.admit(
            sha256 = hash(content),
            contentBase64 = Base64.getEncoder().encodeToString(content),
        ).refined()
        return AddDeclarationRevalidationObservation.observe(
            generation = EvidenceGeneration.parse(generation).refined(),
            target = target,
            currentFile = currentFile,
            provenance = provenance,
            writability = writability,
        ).refined()
    }

    private fun plan(): PlannedAddDeclaration {
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(PREIMAGE),
            proposedDeclaration = "fun added(): Int = 1",
        ).refine().refined()
        val target = AddDeclarationTargetCapability.admit(intent, owner()).refined()
        val preimage = ExactFileContentProof.admit(
            sha256 = hash(PREIMAGE),
            contentBase64 = Base64.getEncoder().encodeToString(PREIMAGE),
        ).refined()
        val postimage = ExactFileContentProof.admit(
            sha256 = hash(POSTIMAGE),
            contentBase64 = Base64.getEncoder().encodeToString(POSTIMAGE),
        ).refined()
        val generation = EvidenceGeneration.parse(7).refined()
        val evidence = AddDeclarationPlanningEvidence.admit(
            intent = intent,
            generation = generation,
            target = target,
            expectedFile = ExpectedFileProof.admit(target, preimage, postimage).refined(),
            declaredWriteSet = DeclaredWriteSet.admit(listOf(target.targetPath)).refined(),
            expectedSemanticDelta = ExpectedAddDeclarationDelta.admit(
                packageName = "sample",
                declarationName = "added",
                declarationKind = AddDeclarationKind.FUNCTION,
            ).refined(),
            verification = AddDeclarationVerificationContract.forGeneration(generation),
            compilerContext = ExpectedAddDeclarationCompilerContext.admit(
                generation = generation,
                projectModelFingerprint = AddDeclarationProjectModelFingerprint.parse(
                    "3".repeat(64),
                ).refined(),
                classpathFingerprint = AddDeclarationClasspathFingerprint.parse(
                    "4".repeat(64),
                ).refined(),
                contextFiles = listOf(
                    AddDeclarationCompilerContextFile.admit(TARGET, hash(PREIMAGE)).refined(),
                ),
                outboundReferenceCount = AddDeclarationOutboundReferenceCount.parse(0).refined(),
            ).refined(),
            compilerEvidence = DetachedCompilerEvidence.admit("{\"proof\":\"complete\"}").refined(),
        ).refined()
        return PlannedAddDeclaration.issue(evidence)
    }

    private fun owner(
        ideaModuleName: String = "kast.indexer.main",
    ): AddDeclarationSourceOwner = AddDeclarationSourceOwner.admit(
        sourceRoot = SOURCE_ROOT,
        ideaModuleName = ideaModuleName,
        gradleBuildRoot = ROOT,
        gradleProjectPath = ":indexer",
        sourceSetName = "main",
    ).refined()

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private fun Refinement<RevalidatedAddDeclaration, AddDeclarationRevalidationRejection>.rejected():
        AddDeclarationRevalidationRejection =
        assertInstanceOf<Refinement.Rejected<AddDeclarationRevalidationRejection>>(this).failure

    private fun hash(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val ROOT = "/workspace/kast"
        const val SOURCE_ROOT = "$ROOT/indexer/src/main/kotlin"
        const val TARGET = "$SOURCE_ROOT/sample/Target.kt"
        const val ALTERNATE_TARGET = "$SOURCE_ROOT/sample/Other.kt"
        val PREIMAGE = "package sample\n".toByteArray()
        val POSTIMAGE = "package sample\n\nfun added(): Int = 1\n".toByteArray()
    }
}
