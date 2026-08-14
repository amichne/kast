package io.github.amichne.kast.change.recovery.filesystem

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationRevalidationObservation
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationSourceProvenance
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.AddDeclarationTargetWritability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.DetachedCompilerEvidence
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationCompilerContext
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecoveryFailure
import io.github.amichne.kast.change.recovery.spi.DurableAddDeclarationRecoveryResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

class FilesystemAddDeclarationRecoveryPreparerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exact before image is durable and idempotent without touching source`() {
        val fixture = fixture()
        val recoveryRoot = Files.createDirectory(tempDir.resolve("recovery")).toRealPath()
        val preparer = open(recoveryRoot)

        val first = assertInstanceOf<DurableAddDeclarationRecoveryResult.Prepared>(
            preparer.prepare(fixture.revalidated.recovery),
        ).recovery
        val second = assertInstanceOf<DurableAddDeclarationRecoveryResult.Prepared>(
            preparer.prepare(fixture.revalidated.recovery),
        ).recovery

        val artifact = recoveryRoot.resolve("${fixture.plan.planId.value}.before")
        assertEquals(first, second)
        assertEquals(fixture.revalidated.recovery, first.material)
        assertArrayEquals(PREIMAGE, Files.readAllBytes(artifact))
        assertArrayEquals(PREIMAGE, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `mismatched existing artifact fails closed without replacement`() {
        val fixture = fixture()
        val recoveryRoot = Files.createDirectory(tempDir.resolve("tampered-recovery")).toRealPath()
        val artifact = recoveryRoot.resolve("${fixture.plan.planId.value}.before")
        Files.write(artifact, "tampered".toByteArray())
        val preparer = open(recoveryRoot)

        val rejected = assertInstanceOf<DurableAddDeclarationRecoveryResult.Rejected>(
            preparer.prepare(fixture.revalidated.recovery),
        )

        assertEquals(DurableAddDeclarationRecoveryFailure.EXISTING_ARTIFACT_MISMATCH, rejected.failure)
        assertArrayEquals("tampered".toByteArray(), Files.readAllBytes(artifact))
        assertArrayEquals(PREIMAGE, Files.readAllBytes(fixture.target))
    }

    @Test
    fun reviewRegression_failedPartialArtifactDoesNotPoisonRetry() {
        val fixture = fixture()
        val recoveryRoot = Files.createDirectory(tempDir.resolve("partial-recovery")).toRealPath()
        val artifact = recoveryRoot.resolve("${fixture.plan.planId.value}.before")
        val failing = assertInstanceOf<FilesystemAddDeclarationRecoveryPreparerOpenResult.Opened>(
            FilesystemAddDeclarationRecoveryPreparer.openWithArtifactWriter(
                recoveryRoot,
                AddDeclarationRecoveryArtifactWriter { path, bytes ->
                    Files.write(path, bytes.copyOf(3))
                    throw IOException("simulated force failure")
                },
            ),
        ).preparer

        assertInstanceOf<DurableAddDeclarationRecoveryResult.Rejected>(
            failing.prepare(fixture.revalidated.recovery),
        )
        assertFalse(Files.exists(artifact), "failed creation left a final partial artifact")
        assertEquals(
            emptyList<Path>(),
            Files.list(recoveryRoot).use { paths ->
                paths.filter { path -> path.fileName.toString().endsWith(".before.tmp") }.toList()
            },
            "failed creation left a staged partial artifact",
        )
        assertInstanceOf<DurableAddDeclarationRecoveryResult.Prepared>(
            open(recoveryRoot).prepare(fixture.revalidated.recovery),
        )
    }

    private fun open(root: Path): FilesystemAddDeclarationRecoveryPreparer =
        assertInstanceOf<FilesystemAddDeclarationRecoveryPreparerOpenResult.Opened>(
            FilesystemAddDeclarationRecoveryPreparer.open(root),
        ).preparer

    private fun fixture(): Fixture {
        val workspaceRoot = tempDir.resolve("workspace").toAbsolutePath().normalize()
        val sourceRoot = Files.createDirectories(workspaceRoot.resolve("src/main/kotlin"))
        val target = sourceRoot.resolve("sample/Target.kt")
        Files.createDirectories(target.parent)
        Files.write(target, PREIMAGE)
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = workspaceRoot.toString(),
            targetPath = target.toString(),
            expectedCurrentSha256 = hash(PREIMAGE),
            proposedDeclaration = "fun added(): Int = 1",
        ).refine().refined()
        val owner = AddDeclarationSourceOwner.admit(
            sourceRoot = sourceRoot.toString(),
            ideaModuleName = "kast.sample.main",
            gradleBuildRoot = workspaceRoot.toString(),
            gradleProjectPath = ":sample",
            sourceSetName = "main",
        ).refined()
        val targetCapability = AddDeclarationTargetCapability.admit(intent, owner).refined()
        val generation = EvidenceGeneration.parse(7).refined()
        val preimage = exact(PREIMAGE)
        val postimage = exact(POSTIMAGE)
        val plan = PlannedAddDeclaration.issue(
            AddDeclarationPlanningEvidence.admit(
                intent = intent,
                generation = generation,
                target = targetCapability,
                expectedFile = ExpectedFileProof.admit(targetCapability, preimage, postimage).refined(),
                declaredWriteSet = DeclaredWriteSet.admit(listOf(targetCapability.targetPath)).refined(),
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
                    targetCapability.targetPath.value,
                    hash(PREIMAGE),
                    0,
                ).refined(),
                compilerEvidence = DetachedCompilerEvidence.admit("{\"complete\":true}").refined(),
            ).refined(),
        )
        val observation = AddDeclarationRevalidationObservation.observe(
            generation = generation,
            target = targetCapability,
            currentFile = preimage,
            provenance = AddDeclarationSourceProvenance.AUTHORED,
            writability = AddDeclarationTargetWritability.WRITABLE,
        ).refined()
        return Fixture(plan, RevalidatedAddDeclaration.admit(plan, observation).refined(), target)
    }

    private fun exact(bytes: ByteArray): ExactFileContentProof = ExactFileContentProof.admit(
        sha256 = hash(bytes),
        contentBase64 = Base64.getEncoder().encodeToString(bytes),
    ).refined()

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private data class Fixture(
        val plan: PlannedAddDeclaration,
        val revalidated: RevalidatedAddDeclaration,
        val target: Path,
    )

    private companion object {
        val PREIMAGE = "package sample\n".toByteArray()
        val POSTIMAGE = "package sample\n\nfun added(): Int = 1\n".toByteArray()
    }
}
