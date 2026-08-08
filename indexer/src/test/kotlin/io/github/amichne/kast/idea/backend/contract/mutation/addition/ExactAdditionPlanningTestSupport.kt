package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.api.contract.query.AddDeclarationPlanQuery
import io.github.amichne.kast.api.contract.query.AddFilePlanQuery
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.CreateFileParentPolicy
import io.github.amichne.kast.api.contract.result.MutationPostconditionStatus
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.ExactAddDeclarationProof
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.protocol.MutationPostconditionFailedException
import io.github.amichne.kast.api.protocol.MutationPostconditionLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.mutation.copiedAdditionKtFile
import io.github.amichne.kast.idea.backend.mutation.exactFileBottomDeclaration
import io.github.amichne.kast.idea.backend.mutation.requireExactProjectPostimage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
internal abstract class ExactAdditionPlanningTestSupport : KastIndexerBackendContractTestFixture() {
    protected val dependencyCollisionFileFixture: TestFixture<PsiFile> =
        secondarySourceRootFixture.psiFileFixture(
            "AdditionDependencyCollisions.kt",
            """
                package demo

                class DependencyCollision

                fun dependencyCollision(value: String): String = value
            """.trimIndent(),
        )
    protected suspend fun additionFailure(
        backend: io.github.amichne.kast.idea.backend.KastIndexerBackend,
        sourceRoot: Path,
        declaration: String,
    ): AdditionProofIncompleteException = try {
        backend.planAddFile(
            AddFilePlanQuery(
                AdditionTargetPath.parse(sourceRoot.resolve("PlannerFailure${System.nanoTime()}.kt").toString()),
                "package demo\n\n$declaration",
            ),
        )
        error("Expected addition planning to fail")
    } catch (failure: AdditionProofIncompleteException) {
        failure
    }

    protected fun sourceRoot(): Path = Path.of(sampleFile.virtualFile.parent.path).toAbsolutePath().normalize()

    protected fun model(
        workspaceRoot: Path,
        sourceRoot: Path,
    ): () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel = model(
        workspaceRoot,
        listOf(association("main", workspaceRoot, ":", "main", sourceRoot)),
    )

    protected fun model(
        workspaceRoot: Path,
        associations: List<IdeaGradleProjectLoadBridge.GradleModuleAssociation>,
    ): () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel = {
        val identities = associations.map { association ->
            IdeaGradleProjectLoadBridge.GradleModuleIdentity(workspaceRoot, association.gradleProjectPath())
        }
        IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            listOf(workspaceRoot),
            true,
            identities,
            associations.zip(identities) { association, identity ->
                IdeaGradleProjectLoadBridge.LoadedGradleModule(association.ideaModuleName(), identity)
            },
            associations.flatMap { association -> association.sourceSets().flatMap { it.sourceRoots() } }.distinct(),
            associations,
        )
    }

    protected fun association(
        moduleName: String,
        workspaceRoot: Path,
        projectPath: String,
        sourceSet: String,
        sourceRoot: Path,
    ): IdeaGradleProjectLoadBridge.GradleModuleAssociation = association(
        moduleName,
        workspaceRoot,
        projectPath,
        sourceSet,
        authoredGradleSourceRoot(sourceRoot),
    )

    protected fun association(
        moduleName: String,
        workspaceRoot: Path,
        projectPath: String,
        sourceSet: String,
        sourceRoot: IdeaGradleProjectLoadBridge.GradleSourceRoot,
    ): IdeaGradleProjectLoadBridge.GradleModuleAssociation =
        IdeaGradleProjectLoadBridge.GradleModuleAssociation(
            moduleName,
            workspaceRoot,
            workspaceRoot,
            projectPath,
            projectPath == ":",
            false,
            listOf(IdeaGradleProjectLoadBridge.GradleSourceSetAssociation(sourceSet, listOf(sourceRoot))),
        )

    protected fun createNestedSourceRoot(broadRoot: Path): Path {
        lateinit var nested: Path
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val directory = VfsUtil.createDirectoryIfMissing(broadRoot.resolve("planner-nested").toString())
                    ?: error("Could not create nested source root")
                val anchor = directory.findChild("NestedAnchor.kt") ?: directory.createChildData(this, "NestedAnchor.kt")
                VfsUtil.saveText(anchor, "package demo.nested\n\nclass NestedAnchor")
                nested = Path.of(directory.path).toAbsolutePath().normalize()
            }
        }
        waitUntilIndexesAreReady(project)
        return nested
    }

    protected fun deleteNestedSourceRoot(nestedRoot: Path) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByNioFile(nestedRoot)
                    ?.delete(this)
            }
        }
        waitUntilIndexesAreReady(project)
    }

    protected fun assertLimitation(
        failure: AdditionProofIncompleteException,
        limitation: AdditionProofLimitation,
    ) = assertEquals(listOf(limitation), failure.limitations)
}
