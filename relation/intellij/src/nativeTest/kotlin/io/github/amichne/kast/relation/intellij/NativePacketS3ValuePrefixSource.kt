@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSimpleFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression

/** The original adapt call and its local consumers precede every producer-adapter assertion. */
internal class NativePacketS3ValuePrefixSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun facts(): S3ValuePrefixFacts =
        ReadAction.compute<S3ValuePrefixFacts, RuntimeException> {
            val psi = psi()
            val execute =
                PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single { it.name == "execute" }
            val calls = PsiTreeUtil.collectElementsOfType(execute, KtCallExpression::class.java)
            val adapt = calls.single { it.calleeExpression?.text == "adapt" }
            val log = calls.single { it.calleeExpression?.text == "log" }
            val binding =
                PsiTreeUtil.collectElementsOfType(execute, KtProperty::class.java).single { it.name == "result" }
            val logRead = log.valueArguments.single().getArgumentExpression() as KtNameReferenceExpression
            val returned = PsiTreeUtil.collectElementsOfType(execute, KtReturnExpression::class.java).single()
            val returnRead = returned.returnedExpression as KtNameReferenceExpression
            assertEquals(binding.initializer, adapt)
            assertEquals(exact(ADAPT_START, ADAPT_END), range(adapt))
            val property = property()
            IntellijReadObservation.None.observedAnalyze(adapt) {
                proveAdapt(call = adapt, expected = property)
                val symbol = binding.symbol
                assertTrue(symbol.isVal)
                assertEquals(KaSymbolOrigin.SOURCE, symbol.origin)
                assertEquals(symbol, logRead.references.filterIsInstance<KtReference>().single().resolveToSymbol())
                assertEquals(symbol, returnRead.references.filterIsInstance<KtReference>().single().resolveToSymbol())
            }
            S3ValuePrefixFacts(
                request = request(property),
                binding = range(binding),
                logRead = range(logRead),
                returnRead = range(returnRead),
                logInvocation = range(log),
            )
        }

    fun request(expected: SymbolSelector): ValueProducerSeedRequest =
        ValueProducerSeedRequest.create(
                enclosing = fixture.callableDeclaration(PacketDeclaration(WIRE, "Call", "execute")),
                anchor = exact(ADAPT_START, ADAPT_END),
                expectedCallable = expected,
                budget = fixture.budget,
                boundary = RelationSearchBoundary.RETAINED_SUBJECT,
            )
            .nativeRefined()

    private fun property(): SymbolSelector {
        val owner = PsiTreeUtil.collectElementsOfType(psi(), KtClass::class.java).single { it.name == "Call" }
        val declaration = owner.primaryConstructorParameters.single { it.name == "adapt" }
        val projected = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(declaration)
        assertTrue(projected is IntellijRelationDeclarationProjection.Projected)
        val evidence = (projected as IntellijRelationDeclarationProjection.Projected).evidence
        assertEquals(CompilerSymbolKind.PROPERTY, evidence.kind)
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_ONLY,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        return SymbolSelector.issue(fixture.lease, scope, evidence)
    }

    private fun KaSession.proveAdapt(call: KtCallExpression, expected: SymbolSelector) {
        val resolved = checkNotNull(call.resolveCall())
        assertTrue(resolved is KaSimpleFunctionCall && resolved.isImplicitInvoke)
        assertEquals("kotlin.Function0.invoke", resolved.signature.symbol.callableId?.asSingleFqName()?.asString())
        assertEquals(KaSymbolOrigin.LIBRARY, resolved.signature.symbol.origin)
        val receiver = resolved.dispatchReceiver as KaExplicitReceiverValue
        assertEquals(range(checkNotNull(call.calleeExpression)), range(receiver.expression))
        val reference = checkNotNull(call.calleeExpression).references.filterIsInstance<KtReference>().single()
        val property =
            when (val symbol = reference.resolveToSymbol()) {
                is KaPropertySymbol -> symbol
                is KaValueParameterSymbol -> checkNotNull(symbol.generatedPrimaryConstructorProperty)
                else -> error("The original adapt reference must resolve its actual source property")
            }
        assertTrue(property.isVal)
        val projected = property.compilerProjection(this)
        assertTrue(projected is IntellijCompilerProjectionResult.Projected)
        val declaration = (projected as IntellijCompilerProjectionResult.Projected).projection
        assertEquals(expected.compilerIdentity, declaration.identity)
        assertEquals(expected.signature, declaration.signature)
        println("S3_PREFIX_K2 seed=761:768 property=594:642 builtin=Function0.invoke source_alias=ACTUAL_RESULT_LOCAL")
    }

    private fun psi(): KtFile {
        val file = checkNotNull(base.findFileByRelativePath("src/fixture/wire/OffersApi.kt"))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    companion object {
        const val WIRE = NativePacketCompositeSuccessSource.WIRE
        private const val ADAPT_START = 761
        private const val ADAPT_END = 768
    }
}

internal data class S3ValuePrefixFacts(
    val request: ValueProducerSeedRequest,
    val binding: ExactDeclarationTextRange,
    val logRead: ExactDeclarationTextRange,
    val returnRead: ExactDeclarationTextRange,
    val logInvocation: ExactDeclarationTextRange,
)
