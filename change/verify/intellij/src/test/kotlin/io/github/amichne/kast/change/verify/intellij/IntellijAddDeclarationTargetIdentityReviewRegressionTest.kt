package io.github.amichne.kast.change.verify.intellij

import io.github.amichne.kast.change.contract.AddDeclarationIntent
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class IntellijAddDeclarationTargetIdentityReviewRegressionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ancestor symlink swap invalidates verified target identity`() {
        val workspace = Files.createDirectory(tempDir.resolve("workspace")).toRealPath()
        val sourceRoot = Files.createDirectories(workspace.resolve("src/main/kotlin")).toRealPath()
        val target = Files.writeString(sourceRoot.resolve("Target.kt"), "package sample\n").toRealPath()
        val capability = capability(workspace, sourceRoot, target)
        val admitted = assertInstanceOf<Refinement.Refined<EffectBoundVerifiedAddDeclarationTarget>>(
            EffectBoundVerifiedAddDeclarationTarget.read(capability),
        ).value
        val external = Files.createDirectory(tempDir.resolve("external")).toRealPath()
        Files.copy(target, external.resolve(target.fileName))
        Files.move(sourceRoot, workspace.resolve("parked-source"))
        Files.createSymbolicLink(sourceRoot, external)

        assertInstanceOf<Refinement.Rejected<EffectBoundVerifiedAddDeclarationTargetFailure>>(
            admitted.revalidate(),
        )
    }

    private fun capability(
        workspace: Path,
        sourceRoot: Path,
        target: Path,
    ): AddDeclarationTargetCapability {
        val request = assertInstanceOf<Refinement.Refined<AddDeclarationIntent>>(
            RawAddDeclarationPlanRequest(
                workspace.toString(),
                target.toString(),
                "a".repeat(64),
                "fun added() {}",
            ).refine(),
        ).value
        val owner = assertInstanceOf<Refinement.Refined<AddDeclarationSourceOwner>>(
            AddDeclarationSourceOwner.admit(
                sourceRoot.toString(),
                "main",
                workspace.toString(),
                ":",
                "main",
            ),
        ).value
        return assertInstanceOf<Refinement.Refined<AddDeclarationTargetCapability>>(
            AddDeclarationTargetCapability.admit(request, owner),
        ).value
    }
}
