package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Two effect-boundary cases share only their case-owned native project and fixed reference oracle. */
abstract class NativeReferenceFixtureTest : HeavyPlatformTestCase() {
    protected abstract val fixtureSdkName: String

    override fun getModuleType() = JavaModuleType.getModuleType()

    protected fun prepareReferenceFixture(): NativeReferenceFixture {
        val base = getOrCreateProjectBaseDir()
        val source =
            WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                base.createChildDirectory(this, "src")
            }
        val sdk = JavaSdk.getInstance().createJdk(fixtureSdkName, System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.sdk = sdk
            model.addContentEntry(source).addSourceFolder(source, false)
        }
        fun file(name: String, text: String): VirtualFile =
            WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                source.createChildData(this, name).also { it.setBinaryContent(text.toByteArray()) }
            }
        val targetFile = file("Target.kt", "package proof\nfun target() = 1\n")
        val callerFile = file("Caller.kt", "package consumer\nfun caller() = proof.target()\n")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val target = (psiManager.findFile(targetFile) as KtFile).declarations.single() as KtNamedFunction
        assertEquals(TARGET_OFFSET, checkNotNull(target.nameIdentifier).textRange.startOffset)
        val request = nativeRelationRequest(base.toNioPath(), targetFile.toNioPath(), TARGET_OFFSET)
        val model =
            WorkspaceSearchScopeModel.compile(
                request.subject.lease.workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = module.name,
                        linkedBuildRoot = base.toNioPath(),
                        gradleProjectPath = ":main",
                        sourceSetName = "main",
                        sourceRoot = source.toNioPath(),
                        sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            )
        return NativeReferenceFixture(
            project = project,
            target = target,
            callerFile = callerFile,
            request = request,
            model = model,
        )
    }

    protected fun assertPositiveControl(fixture: NativeReferenceFixture) {
        val rows = mutableListOf<Pair<String, Int>>()
        assertTrue(
            IntellijReadObservation.None.forEachReference(
                fixture.target,
                fixture.scope(IntellijReadObservation.None).nativeScope,
            ) { reference ->
                rows += reference.element.containingFile.virtualFile.path to reference.element.textRange.startOffset
                assertSame(fixture.target, reference.resolve())
                true
            }
        )
        assertEquals(listOf(fixture.callerFile.path to CALLER_OFFSET), rows)
    }

    private companion object {
        const val TARGET_OFFSET = 18
        const val CALLER_OFFSET = 38
    }
}

class NativeReferenceFixture
internal constructor(
    private val project: Project,
    val target: KtNamedFunction,
    val callerFile: VirtualFile,
    val request: RelationRequest,
    private val model: WorkspaceSearchScopeModelCompilation,
) {
    internal fun scope(observation: IntellijReadObservation): CompiledRelationScope =
        (IntellijRelationScopeCompiler()
                .compile(
                    project = project,
                    request = request,
                    modelCompilation = model,
                    observation = observation,
                ) as IntellijRelationScopeCompilation.Compiled)
            .scope
}
