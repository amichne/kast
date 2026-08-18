package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.util.Base64

class PlannedAddDeclarationTest {
    @Test
    fun `detached plan round trip preserves every fact and canonical identity`() {
        val evidence = evidence()

        val first = PlannedAddDeclaration.issue(evidence)
        val second = PlannedAddDeclaration.issue(evidence)
        val encoded = AddDeclarationPlanCodec.encode(first)
        val decoded = AddDeclarationPlanCodec.decode(encoded).refined()

        assertEquals(first, second)
        assertEquals(first, decoded)
        assertEquals(first.planId, decoded.planId)
        assertEquals(evidence.compilerEvidence, decoded.compilerEvidence)
        assertEquals(listOf(evidence.target.targetPath), decoded.declaredWriteSet.paths)
        assertEquals(evidence.target.owner, decoded.target.owner)
        assertEquals(evidence.generation, decoded.generation)
        assertEquals(evidence.compilerContext, decoded.compilerContext)
    }

    @Test
    fun `codec rejects a validly shaped plan whose identity material was changed`() {
        val plan = PlannedAddDeclaration.issue(evidence())
        val encoded = AddDeclarationPlanCodec.encode(plan)
        val tampered = encoded.replace(
            plan.planId.value,
            "0".repeat(64),
        )

        assertNotEquals(encoded, tampered)
        assertEquals(
            AddDeclarationPlanDecodeFailure.MALFORMED_OR_TAMPERED,
            assertInstanceOf<Refinement.Rejected<AddDeclarationPlanDecodeFailure>>(
                AddDeclarationPlanCodec.decode(tampered),
            ).failure,
        )
    }

    @Test
    fun `raw request and write set refine into stronger operation values`() {
        val preimage = "package sample\n".toByteArray()
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(preimage),
            proposedDeclaration = "fun added(): Int = 1",
        ).refine().refined()

        assertEquals(TARGET, intent.targetPath.value)
        assertEquals(
            DeclaredWriteSetFailure.EMPTY,
            assertInstanceOf<Refinement.Rejected<DeclaredWriteSetFailure>>(
                DeclaredWriteSet.admit(emptyList()),
            ).failure,
        )
    }

    private fun evidence(): AddDeclarationPlanningEvidence {
        val preimageBytes = "package sample\n".toByteArray()
        val postimageBytes = "package sample\n\nfun added(): Int = 1\n".toByteArray()
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(preimageBytes),
            proposedDeclaration = "fun added(): Int = 1",
        ).refine().refined()
        val owner = AddDeclarationSourceOwner.admit(
            sourceRoot = "$ROOT/indexer/src/main/kotlin",
            ideaModuleName = "kast.indexer.main",
            gradleBuildRoot = ROOT,
            gradleProjectPath = ":indexer",
            sourceSetName = "main",
        ).refined()
        val target = AddDeclarationTargetCapability.admit(intent, owner).refined()
        val preimage = ExactFileContentProof.admit(
            sha256 = hash(preimageBytes),
            contentBase64 = Base64.getEncoder().encodeToString(preimageBytes),
        ).refined()
        val postimage = ExactFileContentProof.admit(
            sha256 = hash(postimageBytes),
            contentBase64 = Base64.getEncoder().encodeToString(postimageBytes),
        ).refined()
        val expectedFile = ExpectedFileProof.admit(target, preimage, postimage).refined()
        val delta = ExpectedAddDeclarationDelta.admit(
            packageName = "sample",
            declarationName = "added",
            declarationKind = AddDeclarationKind.FUNCTION,
        ).refined()
        val generation = EvidenceGeneration.parse(7).refined()
        val compilerContext = ExpectedAddDeclarationCompilerContext.admit(
            generation = generation,
            projectModelFingerprint = AddDeclarationProjectModelFingerprint.parse(
                "3".repeat(64),
            ).refined(),
            classpathFingerprint = AddDeclarationClasspathFingerprint.parse(
                "4".repeat(64),
            ).refined(),
            contextFiles = listOf(
                AddDeclarationCompilerContextFile.admit(
                    path = TARGET,
                    sha256 = hash(preimageBytes),
                ).refined(),
            ),
            outboundReferenceCount = AddDeclarationOutboundReferenceCount.parse(0).refined(),
        ).refined()
        return AddDeclarationPlanningEvidence.admit(
            intent = intent,
            generation = generation,
            target = target,
            expectedFile = expectedFile,
            declaredWriteSet = DeclaredWriteSet.admit(listOf(target.targetPath)).refined(),
            expectedSemanticDelta = delta,
            verification = AddDeclarationVerificationContract.forGeneration(generation),
            compilerContext = compilerContext,
            compilerEvidence = DetachedCompilerEvidence.admit("{\"proof\":\"complete\"}").refined(),
        ).refined()
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private fun hash(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val ROOT = "/workspace/kast"
        const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
    }
}
