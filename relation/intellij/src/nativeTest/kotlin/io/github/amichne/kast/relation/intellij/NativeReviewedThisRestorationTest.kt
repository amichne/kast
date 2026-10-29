package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.readAction
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtThisExpression

class NativeReviewedThisRestorationTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-this-restoration-jdk"

    fun testEnclosingDispatchCannotRestoreAsCallbackReceiverRead() = runBlocking {
        val fixture = fixture(paths = listOf(CONTROLS, SCOPE_CONTROLS))
        val owner =
            PacketDeclaration(SCOPE_CONTROLS, "ReviewedScopeShadow", "extensionReceiverCannotBecomeDispatchField")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        assertTrue(
            steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    transfer.relation is ReviewedValueRelation.CallbackReceiverInput
                }
        )
        val request = thisRequest(fixture, owner)
        assertFalse(steps.any { it.source.range == request.anchor && it.source.role == ValueRole.LocalRead })
        val restored =
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        assertEquals(ValueModelSiteRead.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION), restored)
    }

    fun testEmittedCallbackThisRestoresItsExactExecutionOwner() = runBlocking {
        val fixture = fixture(paths = listOf(CONTROLS, SCOPE_CONTROLS))
        val owner = PacketDeclaration(SCOPE_CONTROLS, "ReviewedScopeShadow", "extensionReceiverKeepsItsOwnField")
        val request = thisRequest(fixture, owner)
        val emitted =
            trace(fixture, owner, budget = composedBudget(fixture))
                .map { it.source }
                .single { site ->
                    site.range == request.anchor && site.role == ValueRole.LocalRead
                }
        val restored =
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        assertTrue(
            "The actual emitted callback this must restore: $restored",
            restored is ValueModelSiteRead.Revalidated,
        )
        val site = (restored as ValueModelSiteRead.Revalidated).position.site
        assertEquals(emitted.range, site.range)
        assertEquals(emitted.role, site.role)
        assertEquals(emitted.executionOwner, site.executionOwner)
    }

    private suspend fun thisRequest(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
    ): ValueSiteRevalidationRequest = readAction {
        val virtual = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(FIXTURE_PATH))
        val file = psiManager.findFile(virtual) as KtFile
        val function =
            PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).single { it.name == owner.name }
        val expression = PsiTreeUtil.collectElementsOfType(function, KtThisExpression::class.java).single()
        ValueSiteRevalidationRequest.create(
                enclosing = fixture.markedExpression(owner, "destination").enclosing,
                anchor = sourceValueRange(expression),
                role = ValueSiteRoleClaim.LocalRead,
                budget = composedBudget(fixture),
            )
            .nativeRefined()
    }

    private companion object {
        const val SCOPE_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedScopeReceiverControls.kt"
        const val FIXTURE_PATH = "src/fixture/controls/ReviewedScopeReceiverControls.kt"
    }
}
