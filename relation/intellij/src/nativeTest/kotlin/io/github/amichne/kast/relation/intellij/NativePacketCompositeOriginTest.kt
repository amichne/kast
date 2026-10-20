@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
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
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSimpleFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** S3 keeps its original property invocation separate from the source-visible execute wrapper. */
class NativePacketCompositeOriginTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-composite-origin-jdk"

    fun testOriginalS3K2ResolvesAdaptPropertyAsTheBuiltinInvokeReceiver() {
        val fixture = packetFixture(listOf(OUTCOME, WIRE))
        val selected = selection(fixture)
        val observed = compilerIdentity(selected)
        val signature = observed.callable.signature as CanonicalCompilerSignature.Function
        assertEquals("kotlin.Function0.invoke", signature.qualifiedIdentity.value)
        assertEquals(emptyList<Any>(), signature.valueParameters)
        assertEquals(selected.referenceRange, observed.receiverRange)
        assertEquals(CompilerSymbolKind.PROPERTY, selected.request.expectedCallable.kind)
        println(
            "S3_NATIVE_IDENTITY anchor=${selected.request.anchor} " +
                "property=${selected.request.expectedCallable.compilerIdentity.value} " +
                "propertyRange=${selected.request.expectedCallable.range} " +
                "builtin=${observed.callable.compilerIdentity.value} " +
                "origin=${observed.callable.origin} module=${observed.callable.moduleKind} " +
                "receiver=${observed.receiverRange}"
        )
    }

    fun testOriginalS3PropertySeedAdmitsTheExactAdaptInvocation() {
        val fixture = packetFixture(listOf(OUTCOME, WIRE))
        val selected = selection(fixture)
        val request = selected.request
        assertEquals("adapt", request.expectedCallable.name.value)
        assertEquals(CompilerSymbolKind.PROPERTY, request.expectedCallable.kind)
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        println("S3_ADMISSION anchor=${request.anchor} property=${request.expectedCallable.range} result=$result")
        assertTrue(
            "C3.1 needs the original adapt() seed with its issued Call.adapt property. Result=$result. " +
                "Durable property-origin and builtin binding, C3.2 logging, and C3.3 returns remain NOT_REACHED",
            result is ValueProducerSeedRead.Seeded,
        )
        val seed = (result as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.site.range)
        assertEquals(request.enclosing.compilerIdentity, seed.site.enclosing.compilerIdentity)
        println("S3_RESULT row=C3.1 stage=seed-admission remaining=origin-binding,C3.2,C3.3 status=NOT_RUN")
    }

    private fun selection(fixture: NativePacketValueFixture): S3SourceSelection =
        ReadAction.compute<S3SourceSelection, RuntimeException> {
            val call = originalCall()
            val owner = PsiTreeUtil.collectElementsOfType(psi(), KtClass::class.java).single { it.name == "Call" }
            val parameter = owner.primaryConstructorParameters.single { it.name == "adapt" }
            val projection = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(parameter)
            assertTrue(
                "S3 property selector prerequisite failed: $projection",
                projection is IntellijRelationDeclarationProjection.Projected,
            )
            val evidence = (projection as IntellijRelationDeclarationProjection.Projected).evidence
            assertEquals(CompilerSymbolKind.PROPERTY, evidence.kind)
            val scope =
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_ONLY,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                )
            val property = SymbolSelector.issue(fixture.lease, scope, evidence)
            val request =
                ValueProducerSeedRequest.create(
                        enclosing = fixture.callableDeclaration(PacketDeclaration(WIRE, "Call", "execute")),
                        anchor = range(call),
                        expectedCallable = property,
                        budget = fixture.budget,
                        boundary = RelationSearchBoundary.RETAINED_SUBJECT,
                    )
                    .nativeRefined()
            S3SourceSelection(request, range(checkNotNull(call.calleeExpression)))
        }

    private fun compilerIdentity(selected: S3SourceSelection): S3CompilerIdentity =
        ReadAction.compute<S3CompilerIdentity, RuntimeException> {
            val call = originalCall()
            IntellijReadObservation.None.observedAnalyze(call) {
                val resolved = checkNotNull(call.resolveCall()) { "S3 compiler prerequisite: unresolved adapt()" }
                val symbol = resolved.signature.symbol
                val receiver = resolved.dispatchReceiver
                println(
                    "S3_K2_STAGE anchor=${range(call)} " +
                        "symbol=${symbol.javaClass.simpleName.take(MAX_DIAGNOSTIC_NAME_LENGTH)} " +
                        "receiver=${receiver?.javaClass?.simpleName?.take(MAX_DIAGNOSTIC_NAME_LENGTH)}"
                )
                assertTrue("S3 needs a compiler-resolved named invoke symbol", symbol is KaNamedFunctionSymbol)
                val invoked = symbol as KaNamedFunctionSymbol
                assertEquals("kotlin.Function0.invoke", invoked.callableId?.asSingleFqName()?.asString())
                assertTrue(
                    "S3 compiler call must preserve implicit invoke",
                    resolved is KaSimpleFunctionCall && resolved.isImplicitInvoke,
                )
                val candidates = checkNotNull(call.calleeExpression).references.filterIsInstance<KtReference>()
                val property =
                    candidates
                        .mapNotNull { reference ->
                            when (val actual = reference.resolveToSymbol()) {
                                is KaPropertySymbol -> actual
                                is KaValueParameterSymbol -> actual.generatedPrimaryConstructorProperty
                                else -> null
                            }
                        }
                        .distinct()
                        .single()
                val projected = property.compilerProjection(this)
                assertTrue(
                    "S3 property reference identity failed",
                    projected is IntellijCompilerProjectionResult.Projected,
                )
                assertEquals(
                    selected.request.expectedCallable.signature,
                    (projected as IntellijCompilerProjectionResult.Projected).projection.signature,
                )
                assertTrue("S3 invoke receiver must preserve the adapt reference", receiver is KaExplicitReceiverValue)
                val actual = sourceLessCallable(invoked)
                assertTrue("S3 builtin identity failed", actual is IntellijK2ResolvedDeclaration.SourceLess)
                S3CompilerIdentity(
                    (actual as IntellijK2ResolvedDeclaration.SourceLess).callable,
                    range((receiver as KaExplicitReceiverValue).expression),
                )
            }
        }

    private fun originalCall(): KtCallExpression {
        val execute =
            PsiTreeUtil.collectElementsOfType(psi(), KtNamedFunction::class.java).single { it.name == "execute" }
        return PsiTreeUtil.collectElementsOfType(execute, KtCallExpression::class.java).single {
            it.calleeExpression?.text == "adapt"
        }
    }

    private fun psi(): KtFile {
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath("src/fixture/wire/OffersApi.kt"))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    private companion object {
        const val MAX_DIAGNOSTIC_NAME_LENGTH = 80
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        const val WIRE = "src/main/kotlin/fixture/wire/OffersApi.kt"
    }
}

private data class S3SourceSelection(
    val request: ValueProducerSeedRequest,
    val referenceRange: ExactDeclarationTextRange,
)

private data class S3CompilerIdentity(val callable: SourceLessCallable, val receiverRange: ExactDeclarationTextRange)
