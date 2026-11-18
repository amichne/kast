package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression

abstract class NativeDependencyCallbackFixture : NativeReferenceFixtureTest() {
    protected fun fixture(
        expression: String,
        sources: Boolean,
        helper: String = "",
        libraryJar: Path? = null,
    ): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            val libraryRoot = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib")
            ModuleRootModificationUtil.updateModel(module) { model ->
                val library = model.moduleLibraryTable.createLibrary("case-stdlib")
                val roots = library.modifiableModel
                roots.addRoot(
                    "jar://${libraryJar ?: libraryRoot.resolve("kotlin-stdlib.jar")}!/",
                    OrderRootType.CLASSES,
                )
                if (sources)
                    roots.addRoot("jar://${libraryRoot.resolve("kotlin-stdlib-sources.jar")}!/", OrderRootType.SOURCES)
                roots.commit()
            }
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(
                    "package consumer\n${helper}fun caller() { $expression }\n".toByteArray()
                )
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }

    protected fun read(
        fixture: NativeReferenceFixture,
        observation: IntellijReadObservation = IntellijReadObservation.None,
        ownerName: String = "caller",
    ): io.github.amichne.kast.relation.contract.CallbackInvocationFlow {
        val file = psiManager.findFile(fixture.callerFile) as KtFile
        val caller =
            PsiTreeUtil.collectElementsOfType(file, org.jetbrains.kotlin.psi.KtNamedDeclaration::class.java).single {
                it.name == ownerName
            }
        val literal = checkNotNull(PsiTreeUtil.findChildOfType(caller, KtLambdaExpression::class.java)).functionLiteral
        val projection = IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot, observation)
        val owner = (projection.project(caller) as IntellijRelationDeclarationProjection.Projected).evidence
        val collector = IntellijRelationCollector(fixture.request, observation = observation)
        val result =
            readCallbackInvocationFlow(
                boundary = literal,
                lexicalOwner = owner,
                scope = fixture.scope(observation),
                projection = projection,
                admitWork = collector::admitCallbackWork,
                summaries = CallbackParameterSummaries(fixture.request.budget, observation),
                callbackExpiry = collector::callbackExpiry,
            )
        assertTrue("Expected dependency proof, received $result", result is CallbackInvocationFlowRead.Observed)
        return (result as CallbackInvocationFlowRead.Observed).flow
    }
}
