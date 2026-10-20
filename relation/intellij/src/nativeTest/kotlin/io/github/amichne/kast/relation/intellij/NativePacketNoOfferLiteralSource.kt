@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueField
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** The literal is a separate control-only source position. The original S2 producer stays unchanged. */
internal class NativePacketNoOfferLiteralSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun facts(): NoOfferLiteralFacts {
        val expression =
            fixture.argumentExpression(
                PacketDeclaration(FEATURE, "OffersProcessorImpl", "getOffer"),
                "FeatureError",
                "\"NO_OFFER\"",
            )
        assertEquals(exact(LITERAL_START, LITERAL_END), expression.anchor)
        val constructor = fixture.callableDeclaration(PacketDeclaration(FEATURE, "FeatureError", "<init>"))
        val owner = ReadAction.compute<ValueExecutionOwner, RuntimeException> { proveLiteral(expression, constructor) }
        val field = NativePacketCompositeSuccessSource(project, base, fixture).field(FEATURE, "FeatureError", "reason")
        return NoOfferLiteralFacts(expression = expression, constructor = constructor, reason = field, owner = owner)
    }

    fun resolve(facts: NoOfferLiteralFacts): ValueSite {
        val domain =
            RelationRequest.start(
                RelationEndpoint.subject(facts.expression.enclosing),
                RelationMeaning.References,
                fixture.budget,
            )
        val resolved = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = facts.expression.enclosing,
                    anchor = facts.expression.anchor,
                    domain = domain,
                    model = fixture.model,
                )
        }
        assertTrue("The K2-proved constant source position must resolve", resolved is ValueSiteResolution.Found)
        return (resolved as ValueSiteResolution.Found).site.also { actual ->
            assertEquals(facts.owner, actual.executionOwner)
            assertEquals(facts.expression.anchor, actual.range)
        }
    }

    fun read(site: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("The constant-source control needs a native read", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun proveLiteral(expression: PacketExpression, expected: SymbolSelector): ValueExecutionOwner {
        val file = checkNotNull(base.findFileByRelativePath("src/fixture/feature/OffersProcessor.kt"))
        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
        val literal =
            PsiTreeUtil.collectElementsOfType(psi, KtStringTemplateExpression::class.java).single {
                range(it) == expression.anchor
            }
        assertTrue(literal.entries.single() is KtLiteralStringTemplateEntry)
        assertEquals("NO_OFFER", literal.entries.single().text)
        val call = checkNotNull(PsiTreeUtil.getParentOfType(literal, KtCallExpression::class.java))
        val body = checkNotNull(PsiTreeUtil.getParentOfType(call, KtFunctionLiteral::class.java))
        assertEquals(exact(CONSTRUCTOR_START, CONSTRUCTOR_END), range(call))
        assertEquals(exact(CALLBACK_START, CALLBACK_END), range(body))
        assertEquals(call, body.bodyExpression?.statements?.single())
        return IntellijReadObservation.None.observedAnalyze(literal) {
            val actual = checkNotNull(call.resolveCall())
            val constructor = actual.signature.symbol as KaConstructorSymbol
            assertEquals(KaSymbolOrigin.SOURCE, constructor.origin)
            val projection = constructor.compilerProjection(this) as IntellijCompilerProjectionResult.Projected
            assertEquals(expected.compilerIdentity, projection.projection.identity)
            val parameter = constructor.valueParameters.single()
            assertEquals("reason", parameter.name.asString())
            assertEquals(parameter, actual.valueArgumentMapping[literal]?.symbol)
            val type = literal.expressionType as KaClassType
            assertEquals("kotlin.String", type.classId.asSingleFqName().asString())
            assertTrue(!type.isMarkedNullable)
            assertNull(body.symbol.receiverParameter)
            assertTrue(body.symbol.valueParameters.isEmpty())
            assertTrue(body.symbol.contextReceivers.isEmpty())
            assertTrue(call.isUsedAsResultOfLambda)
            literalOwner(expression.enclosing, range(body))
        }
    }

    private fun literalOwner(host: SymbolSelector, bodyRange: ExactDeclarationTextRange): ValueExecutionOwner {
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(host.file, bodyRange),
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = emptyList(),
                    rawTypeParameterCount = 0,
                )
                .nativeRefined() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(host.file, bodyRange, signature).nativeRefined()
        return ValueExecutionOwner.fromCompiler(RelationEndpoint.subject(host), body, fixture.lease.identity)
            .nativeRefined()
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        const val LITERAL_START = 1007
        const val LITERAL_END = 1017
        const val CONSTRUCTOR_START = 985
        const val CONSTRUCTOR_END = 1018
        const val CALLBACK_START = 983
        const val CALLBACK_END = 1020
    }
}

internal data class NoOfferLiteralFacts(
    val expression: PacketExpression,
    val constructor: SymbolSelector,
    val reason: ValueField,
    val owner: ValueExecutionOwner,
)
