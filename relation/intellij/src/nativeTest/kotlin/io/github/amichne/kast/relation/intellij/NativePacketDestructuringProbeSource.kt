@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral

/** Exact syntax and K2 declarations supply expectations before the adapter receives the initializer. */
internal class NativePacketDestructuringProbeSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun expectation(): DestructuringProbeExpectation {
        val syntax = syntax()
        val host = fixture.callableDeclaration(PacketDeclaration(FEATURE, "OffersProcessorImpl", "getOffer"))
        val body =
            IntellijReadObservation.None.observedAnalyze(syntax.literal) {
                anonymousBody(host, syntax.literal)
            }
        val owner =
            ValueExecutionOwner.fromCompiler(RelationEndpoint.subject(host), body, fixture.lease.identity)
                .nativeRefined()
        return DestructuringProbeExpectation(host, range(checkNotNull(syntax.declaration.initializer)), owner)
    }

    fun capture() {
        val syntax = syntax()
        val expected = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "OutcomeScope", "component1"))
        val sequential = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "Companion", "sequential"))
        val expectation = expectation()
        IntellijReadObservation.None.observedAnalyze(syntax.entry) {
            val resolved = checkNotNull(syntax.entry.resolveCall()) { "Original destructuring entry did not resolve" }
            assertTrue("The operator needs an actual K2 function call", resolved is KaFunctionCall<*>)
            val call = resolved as KaFunctionCall<*>
            val projected = call.signature.symbol.compilerProjection(this)
            assertTrue(projected is IntellijCompilerProjectionResult.Projected)
            val declaration = (projected as IntellijCompilerProjectionResult.Projected).projection
            assertEquals(expected.compilerIdentity, declaration.identity)
            assertEquals(expected.signature, declaration.signature)
            assertEquals(KaSymbolOrigin.SOURCE, call.signature.symbol.origin)
            val sequentialCall = checkNotNull(syntax.sequential.resolveCall())
            val sequentialProjection = sequentialCall.signature.symbol.compilerProjection(this)
            assertTrue(sequentialProjection is IntellijCompilerProjectionResult.Projected)
            assertEquals(
                sequential.compilerIdentity,
                (sequentialProjection as IntellijCompilerProjectionResult.Projected).projection.identity,
            )
            recordSignature(call, declaration.signature as CanonicalCompilerSignature.Function)
            val anonymous = syntax.literal.symbol
            println(
                "DESTRUCTURING_BODY range=${range(syntax.literal)} " +
                    "identity=${expectation.executionOwner.identity.body.compiler.value}"
            )
            recordDestructuringReceiver(DestructuringReceiverSlot.EXTENSION, call.extensionReceiver, anonymous)
            recordDestructuringReceiver(DestructuringReceiverSlot.DISPATCH, call.dispatchReceiver, anonymous)
        }
        println("DESTRUCTURING_K2 status=CHARACTERIZED adapter_admission=NOT_RUN full_S2_route=NOT_RUN")
    }

    private fun syntax(): DestructuringProbeSyntax {
        val file = checkNotNull(base.findFileByRelativePath("src/fixture/feature/OffersProcessor.kt"))
        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
        val entry =
            PsiTreeUtil.collectElementsOfType(psi, KtDestructuringDeclarationEntry::class.java).single { candidate ->
                candidate.name == "response"
            }
        val declaration = entry.parent as KtDestructuringDeclaration
        val literal = checkNotNull(PsiTreeUtil.getParentOfType(entry, KtFunctionLiteral::class.java))
        val sequential =
            PsiTreeUtil.collectElementsOfType(psi, KtCallExpression::class.java).single { candidate ->
                candidate.calleeExpression?.text == "sequential"
            }
        assertTrue(sequential.lambdaArguments.single().getLambdaExpression()?.functionLiteral === literal)
        assertEquals(exact(DECLARATION_START, DECLARATION_END), range(declaration))
        assertEquals(exact(ENTRY_START, ENTRY_END), range(entry))
        assertEquals(exact(INITIALIZER_START, INITIALIZER_END), range(checkNotNull(declaration.initializer)))
        println(
            "DESTRUCTURING_SYNTAX declaration=${range(declaration)} entry=${range(entry)} " +
                "initializer=${range(checkNotNull(declaration.initializer))}"
        )
        return DestructuringProbeSyntax(
            entry = entry,
            declaration = declaration,
            literal = literal,
            sequential = sequential,
        )
    }

    private fun KaSession.anonymousBody(
        host: SymbolSelector,
        literal: KtFunctionLiteral,
    ): RelationCallableBody.Anonymous {
        val symbol = literal.symbol
        val exact = range(literal)
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(host.file, exact),
                    rawReceiverType = symbol.receiverParameter?.returnType?.toString(),
                    rawContextReceiverTypes = symbol.contextReceivers.map { it.type.toString() },
                    rawValueParameterTypes = symbol.valueParameters.map { it.returnType.toString() },
                    rawTypeParameterCount = 0,
                )
                .nativeRefined() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(host.file, exact, signature).nativeRefined()
    }

    private fun recordSignature(call: KaFunctionCall<*>, declared: CanonicalCompilerSignature.Function) {
        println(
            "DESTRUCTURING_SIGNATURE callable=${declared.qualifiedIdentity.value} receiver=${declared.receiver} " +
                "parameters=${declared.valueParameters.map { it.value }} " +
                "contexts=${declared.contextReceivers.map { it.value }} " +
                "typeParameters=${declared.typeParameterCount.value}"
        )
        println(
            "DESTRUCTURING_ACTUAL diagnosticReceiver=${call.signature.receiverType} " +
                "diagnosticParameters=${call.signature.valueParameters.map { it.returnType.toString() }} " +
                "diagnosticResult=${call.signature.returnType} argumentCount=${call.valueArgumentMapping.size}"
        )
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        const val DECLARATION_START = 801
        const val DECLARATION_END = 917
        const val ENTRY_START = 806
        const val ENTRY_END = 814
        const val INITIALIZER_START = 818
        const val INITIALIZER_END = 917
    }
}

private data class DestructuringProbeSyntax(
    val entry: KtDestructuringDeclarationEntry,
    val declaration: KtDestructuringDeclaration,
    val literal: KtFunctionLiteral,
    val sequential: KtCallExpression,
)

internal data class DestructuringProbeExpectation(
    val enclosing: SymbolSelector,
    val initializer: ExactDeclarationTextRange,
    val executionOwner: ValueExecutionOwner,
)
