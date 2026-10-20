@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** A virtual getter cannot establish an unqualified stored-field transfer. */
class NativeStoredFieldDispatchTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "stored-field-dispatch-jdk"

    override fun runInDispatchThread() = false

    fun testOpenPrimaryPropertyKeepsAnUnsupportedFrontier() {
        assertUnsupported(read("openPrimary", KaSymbolModality.OPEN))
    }

    fun testOpenBodyPropertyKeepsAnUnsupportedFrontier() {
        assertUnsupported(read("openBody", KaSymbolModality.OPEN))
    }

    fun testFinalStoredPropertyKeepsItsExactFieldTransfer() {
        val step = read("finalStored", KaSymbolModality.FINAL)
        assertTrue(step.obligations.isEmpty())
        val transfer = step.transfers.single()
        assertEquals(ValueTransferKind.FIELD_READ, transfer.kind)
        val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.ReadField
        assertEquals("stable", operation.field.declaration.name.value)
        assertEquals(step.source.enclosing, transfer.target.enclosing)
    }

    private fun assertUnsupported(step: ValueFlowStep) {
        assertTrue("A virtual getter acquired an unqualified field transfer", step.transfers.isEmpty())
        assertEquals(listOf(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY), step.obligations.map { it.cause })
        assertEquals(step.source, step.obligations.single().site)
    }

    private fun read(functionName: String, modality: KaSymbolModality): ValueFlowStep {
        val prepared = prepare(functionName, modality)
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = prepared.request.source.enclosing.lease,
                    request = prepared.request,
                    model = prepared.model,
                )
        }
        assertTrue("Field fixture failed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun prepare(functionName: String, modality: KaSymbolModality): StoredFieldRead {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(STORED_FIELD_SOURCE.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return ReadAction.compute<StoredFieldRead, RuntimeException> {
            val file = psiManager.findFile(fixture.callerFile) as KtFile
            val function = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == functionName }
            val expression = PsiTreeUtil.findChildrenOfType(function, KtDotQualifiedExpression::class.java).single()
            val receiver = expression.receiverExpression
            IntellijReadObservation.None.observedAnalyze(expression) {
                val property = expression.resolveCall()?.signature?.symbol as KaKotlinPropertySymbol
                assertEquals(modality, property.modality)
                assertEquals(modality, checkNotNull(property.getter).modality)
                assertTrue(property.hasBackingField)
                assertFalse(checkNotNull(property.getter).isNotDefault)
            }
            val projection = IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot)
            val evidence = (projection.project(function) as IntellijRelationDeclarationProjection.Projected).evidence
            val enclosing =
                RelationEndpoint.resolve(
                        fixture.request.subject.lease,
                        fixture.request.searchScope,
                        evidence,
                    )
                    .nativeRefined()
            val site = ValueSite.fromCompiler(enclosing, range(receiver), ValueRole.LocalRead).nativeRefined()
            val budget =
                RelationBudget(fixture.request.budget.resources, RelationByteLimit.parse(1_000_000L).nativeRefined())
            StoredFieldRead(ValueFlowRequest(site, budget, fixture.request.boundary), sourceModel(fixture))
        }
    }

    private fun sourceModel(fixture: NativeReferenceFixture): WorkspaceSearchScopeModelCompilation =
        WorkspaceSearchScopeModel.compile(
            fixture.request.subject.lease.workspaceRoot,
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                WorkspaceSourceRootBoundary(
                    ideaModuleName = module.name,
                    linkedBuildRoot = fixture.callerFile.parent.parent.toNioPath(),
                    gradleProjectPath = ":main",
                    sourceSetName = "main",
                    sourceRoot = fixture.callerFile.parent.toNioPath(),
                    sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                )
            ),
        )
}

private data class StoredFieldRead(val request: ValueFlowRequest, val model: WorkspaceSearchScopeModelCompilation)

private val STORED_FIELD_SOURCE =
    """
    package proof

    open class Base(open val value: Int, val stable: Int)
    class Constant(value: Int) : Base(value, value) {
        override val value: Int get() = 0
    }
    open class BodyBase { open val value: Int = 7 }
    class BodyConstant : BodyBase() { override val value: Int get() = 0 }
    fun openPrimary(): Int {
        val base: Base = Constant(7)
        return base.value
    }
    fun openBody(): Int {
        val base: BodyBase = BodyConstant()
        return base.value
    }
    fun finalStored(): Int {
        val base: Base = Constant(7)
        return base.stable
    }
    """
        .trimIndent()
