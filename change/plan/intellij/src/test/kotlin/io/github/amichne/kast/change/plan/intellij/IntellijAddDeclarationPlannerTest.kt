package io.github.amichne.kast.change.plan.intellij

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.DetachedCompilerEvidence
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationCompilerContext
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.plan.spi.AddDeclarationEvidenceResult
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningLimitation
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningRejection
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class IntellijAddDeclarationPlannerTest {
    @Test
    fun `planner returns a deterministic detached plan from exact matching evidence`() = runTest {
        val evidence = evidence()
        val planner = IntellijAddDeclarationPlanner {
            AddDeclarationEvidenceResult.Proven(evidence)
        }

        val first = assertInstanceOf<AddDeclarationPlanningResult.Planned>(
            planner.plan(evidence.intent),
        ).plan
        val second = assertInstanceOf<AddDeclarationPlanningResult.Planned>(
            planner.plan(evidence.intent),
        ).plan

        assertEquals(first, second)
        assertEquals(evidence.generation, first.generation)
        assertEquals(listOf(evidence.target.targetPath), first.declaredWriteSet.paths)
    }

    @Test
    fun `planner preserves typed evidence rejection without creating a plan`() = runTest {
        val evidence = evidence()
        val expected = AddDeclarationPlanningRejection.of(
            AddDeclarationPlanningLimitation.PROJECT_MODEL_INCOMPLETE,
        )
        val planner = IntellijAddDeclarationPlanner {
            AddDeclarationEvidenceResult.Rejected(expected)
        }

        val rejected = assertInstanceOf<AddDeclarationPlanningResult.Rejected>(
            planner.plan(evidence.intent),
        )

        assertEquals(expected, rejected.rejection)
    }

    private fun evidence(): AddDeclarationPlanningEvidence {
        val before = "package sample\n".toByteArray()
        val after = "package sample\n\nfun added(): Int = 1\n".toByteArray()
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(before),
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
            hash(before),
            Base64.getEncoder().encodeToString(before),
        ).refined()
        val postimage = ExactFileContentProof.admit(
            hash(after),
            Base64.getEncoder().encodeToString(after),
        ).refined()
        val generation = EvidenceGeneration.parse(11).refined()
        return AddDeclarationPlanningEvidence.admit(
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
            compilerContext = ExpectedAddDeclarationCompilerContext.admitSingleSource(
                generation,
                "3".repeat(64),
                "4".repeat(64),
                TARGET,
                hash(before),
                0,
            ).refined(),
            compilerEvidence = DetachedCompilerEvidence.admit("{\"complete\":true}").refined(),
        ).refined()
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private companion object {
        const val ROOT = "/workspace/kast"
        const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
    }
}
