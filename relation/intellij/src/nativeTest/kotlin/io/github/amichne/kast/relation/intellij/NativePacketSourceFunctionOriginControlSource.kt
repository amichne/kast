@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
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
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSimpleFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter

/** Exact K2 calls and source properties supply expectations without the producer-origin factory. */
internal class NativePacketSourceFunctionOriginControlSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun call(method: String, supplier: String, index: Int = 0): SourceFunctionControlCall =
        ReadAction.compute<SourceFunctionControlCall, RuntimeException> {
            val function = functions().single { it.name == method }
            val call =
                PsiTreeUtil.collectElementsOfType(function, KtCallExpression::class.java)
                    .filter { it.calleeExpression?.text == supplier }
                    .sortedBy { it.textOffset }[index]
            val expected = property(supplier)
            val host = fixture.callableDeclaration(PacketDeclaration(PATH, OWNER, method))
            val signature =
                IntellijReadObservation.None.observedAnalyze(call) {
                    sameSupplierTypes()
                    proveInvoke(call = call, supplier = supplier, expected = expected)
                }
            val request =
                ValueProducerSeedRequest.create(
                        enclosing = host,
                        anchor = range(call),
                        expectedCallable = expected,
                        budget = fixture.budget,
                        boundary = RelationSearchBoundary.RETAINED_SUBJECT,
                    )
                    .nativeRefined()
            SourceFunctionControlCall(request, range(checkNotNull(call.calleeExpression)), signature)
        }

    fun sibling(): SymbolSelector = ReadAction.compute<SymbolSelector, RuntimeException> { property(SIBLING) }

    private fun KaSession.proveInvoke(
        call: KtCallExpression,
        supplier: String,
        expected: SymbolSelector,
    ): CanonicalCompilerSignature.Function {
        val resolved = checkNotNull(call.resolveCall())
        assertTrue(resolved is KaSimpleFunctionCall && resolved.isImplicitInvoke)
        val symbol = resolved.signature.symbol
        assertEquals("kotlin.Function0.invoke", symbol.callableId?.asSingleFqName()?.asString())
        assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
        val receiver = resolved.dispatchReceiver as KaExplicitReceiverValue
        assertEquals(checkNotNull(call.calleeExpression).originalElement, receiver.expression.originalElement)
        val name = call.calleeExpression as KtNameReferenceExpression
        val actual = actualProperty(name)
        val parameter = owner().primaryConstructorParameters.single { it.name == supplier }
        assertEquals(parameterProperty(parameter), actual)
        assertEquals(KaSymbolOrigin.SOURCE, actual.origin)
        assertEquals(supplier != MUTABLE, actual.isVal)
        val projected = actual.compilerProjection(this)
        assertTrue(projected is IntellijCompilerProjectionResult.Projected)
        val declaration = (projected as IntellijCompilerProjectionResult.Projected).projection
        assertEquals(expected.compilerIdentity, declaration.identity)
        assertEquals(expected.signature, declaration.signature)
        if (supplier == MUTABLE) proveReassignment(call, actual)
        val builtin = symbol.compilerProjection(this)
        assertTrue(builtin is IntellijCompilerProjectionResult.Projected)
        println(
            "SOURCE_FUNCTION_CONTROL_K2 supplier=$supplier call=${range(call)} " +
                "reference=${range(name)} isVal=${actual.isVal}"
        )
        return (builtin as IntellijCompilerProjectionResult.Projected).projection.signature
            as CanonicalCompilerSignature.Function
    }

    private fun KaSession.sameSupplierTypes() {
        val parameters = owner().primaryConstructorParameters
        val properties = parameters.map { parameter -> parameterProperty(parameter) }
        assertEquals(PROPERTY_COUNT, properties.size)
        val types = properties.map { property ->
            val projection = property.compilerProjection(this)
            assertTrue(projection is IntellijCompilerProjectionResult.Projected)
            val signature =
                (projection as IntellijCompilerProjectionResult.Projected).projection.signature
                    as CanonicalCompilerSignature.Property
            signature.returnType
        }
        assertEquals(1, types.toSet().size)
    }

    private fun KaSession.proveReassignment(call: KtCallExpression, expected: KaPropertySymbol) {
        val function = checkNotNull(PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java))
        val assignment =
            PsiTreeUtil.collectElementsOfType(function, KtBinaryExpression::class.java).single {
                it.operationToken == KtTokens.EQ
            }
        val left = assignment.left as KtNameReferenceExpression
        val right = assignment.right as KtNameReferenceExpression
        assertEquals(expected, actualProperty(left))
        val parameter = function.symbol.valueParameters.single()
        assertEquals(parameter, right.references.filterIsInstance<KtReference>().single().resolveToSymbol())
        assertTrue(assignment.textOffset < call.textOffset)
        assertFalse(expected.isVal)
    }

    private fun KaSession.actualProperty(name: KtNameReferenceExpression): KaPropertySymbol =
        when (val symbol = name.references.filterIsInstance<KtReference>().single().resolveToSymbol()) {
            is KaPropertySymbol -> symbol
            is KaValueParameterSymbol -> checkNotNull(symbol.generatedPrimaryConstructorProperty)
            else -> error("The supplier reference must resolve to its actual constructor property")
        }

    private fun KaSession.parameterProperty(parameter: KtParameter): KaPropertySymbol =
        when (val symbol = parameter.symbol) {
            is KaPropertySymbol -> symbol
            is KaValueParameterSymbol -> checkNotNull(symbol.generatedPrimaryConstructorProperty)
            else -> error("The constructor parameter must declare an actual source property")
        }

    private fun property(name: String): SymbolSelector {
        val parameter = owner().primaryConstructorParameters.single { it.name == name }
        val projection = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(parameter)
        assertTrue(projection is IntellijRelationDeclarationProjection.Projected)
        val evidence = (projection as IntellijRelationDeclarationProjection.Projected).evidence
        assertEquals(CompilerSymbolKind.PROPERTY, evidence.kind)
        return SymbolSelector.issue(
            fixture.lease,
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_ONLY,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            ),
            evidence,
        )
    }

    private fun owner(): KtClass = PsiTreeUtil.collectElementsOfType(psi(), KtClass::class.java).single()

    private fun functions() = PsiTreeUtil.collectElementsOfType(psi(), KtNamedFunction::class.java)

    private fun psi(): KtFile {
        val file = checkNotNull(base.findFileByRelativePath("src/fixture/controls/SourceFunctionOriginControls.kt"))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    companion object {
        const val PATH = "src/main/kotlin/fixture/controls/SourceFunctionOriginControls.kt"
        const val PRIMARY = "primarySupplier"
        const val SIBLING = "siblingSupplier"
        const val MUTABLE = "mutableSupplier"
        private const val OWNER = "SourceFunctionOriginControls"
        private const val PROPERTY_COUNT = 3
    }
}

internal data class SourceFunctionControlCall(
    val request: ValueProducerSeedRequest,
    val reference: ExactDeclarationTextRange,
    val signature: CanonicalCompilerSignature.Function,
)
