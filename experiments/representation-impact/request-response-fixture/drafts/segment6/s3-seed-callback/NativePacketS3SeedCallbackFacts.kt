@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSimpleFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Actual K2 argument and receiver mappings determine the expected supplier, formal, and contrasting source positions.
 */
internal class NativePacketS3SeedCallbackFacts(private val project: Project, private val base: VirtualFile) {
    fun read(source: ValueSite): S3SeedCallbackFacts =
        ReadAction.compute<S3SeedCallbackFacts, RuntimeException> {
            val syntax = syntax()
            val projection = IntellijK2RelationProjection(project, source.enclosing.lease.workspaceRoot)
            val host = endpoint(projection, syntax.caller, source)
            val wrongFile =
                (projection.detach(syntax.transform.containingFile.virtualFile) as IntellijDetachedRelationFile.Found)
                    .identity
            val wrongCall = RelationOccurrence.fromSource(wrongFile, range(syntax.transform))
            IntellijReadObservation.None.observedAnalyze(syntax.invoked) { describe(source, syntax, host, wrongCall) }
        }

    private fun syntax(): S3SeedCallbackSyntax {
        val wire = psi(NativePacketCompositeSuccessSource.WIRE)
        val execute = functions(wire).single { it.name == "execute" }
        val log = calls(execute).single { it.calleeExpression?.text == "log" }
        val client = psi(NativePacketCompositeSuccessSource.CLIENT)
        val caller = functions(client).single { it.name == "readOffers" && it.hasBody() }
        val invoked = calls(caller).single { it.calleeExpression?.text == "execute" }
        val supplied = invoked.valueArguments.single().getArgumentExpression() as KtLambdaExpression
        val mapped = calls(caller).single { it.calleeExpression?.text == "map" }
        val other = checkNotNull(mapped.lambdaArguments.single().getLambdaExpression()).functionLiteral
        val outcome = psi(NativePacketCompositeSuccessSource.OUTCOME)
        val transform =
            calls(functions(outcome).single { it.name == "map" }).single { it.calleeExpression?.text == "transform" }
        return S3SeedCallbackSyntax(execute, log, caller, invoked, supplied, other, transform)
    }

    private fun KaSession.describe(
        source: ValueSite,
        syntax: S3SeedCallbackSyntax,
        host: RelationEndpoint,
        wrongCall: RelationOccurrence,
    ): S3SeedCallbackFacts {
        val resolved = checkNotNull(syntax.invoked.resolveCall())
        assertEquals(KaSymbolOrigin.SOURCE, resolved.signature.symbol.origin)
        assertEquals(syntax.execute.originalElement, checkNotNull(resolved.signature.symbol.psi).originalElement)
        val parameter = resolved.signature.symbol.valueParameters.single()
        assertEquals(parameter, resolved.valueArgumentMapping[syntax.supplied]?.symbol)
        assertEquals("log", parameter.name.asString())
        assertEquals(
            syntax.execute.valueParameters.single().originalElement,
            checkNotNull(parameter.psi).originalElement,
        )
        proveInvocation(source, syntax.log, syntax.execute)
        val formal = formal(syntax.supplied.functionLiteral, host)
        val wrong = owner(syntax.other, host)
        val transformed = checkNotNull(syntax.transform.resolveCall())
        assertEquals("kotlin.Function1.invoke", transformed.signature.symbol.callableId?.asSingleFqName()?.asString())
        println(
            "S3_SEED_CALLBACK_K2 seed=761:768 actual=${source.range} stack=EMPTY " +
                "invoke=${range(syntax.log)} caller=${range(syntax.invoked.valueInvocationExpression())} " +
                "supplier=${range(syntax.supplied.functionLiteral)} formal=ACTUAL_IMPLICIT_0 " +
                "wrongSupplier=${range(syntax.other)} wrongInvocation=${range(syntax.transform)}"
        )
        return S3SeedCallbackFacts(
            caller = host,
            callerRange = range(syntax.invoked.valueInvocationExpression()),
            parameter =
                RelationOccurrence.fromSource(source.enclosing.file, range(syntax.execute.valueParameters.single())),
            formal = formal,
            selected = RelationOccurrence.fromSource(source.enclosing.file, range(syntax.log)),
            wrongSupplier = wrong,
            wrongInvocation = wrongCall,
        )
    }

    private fun KaSession.proveInvocation(source: ValueSite, log: KtCallExpression, execute: KtNamedFunction) {
        val resolved = checkNotNull(log.resolveCall())
        assertTrue(resolved is KaSimpleFunctionCall && resolved.isImplicitInvoke)
        assertEquals("kotlin.Function1.invoke", resolved.signature.symbol.callableId?.asSingleFqName()?.asString())
        val receiver = resolved.dispatchReceiver as KaExplicitReceiverValue
        assertEquals(range(checkNotNull(log.calleeExpression)), range(receiver.expression))
        val symbol =
            checkNotNull(log.calleeExpression).references.filterIsInstance<KtReference>().single().resolveToSymbol()
        assertEquals(execute.symbol.valueParameters.single(), symbol)
        val role = source.role as ValueRole.FunctionArgument
        assertEquals(range(log), role.call.range)
        assertEquals(source.executionOwner, role.call.executionOwner)
    }

    private fun KaSession.formal(literal: KtFunctionLiteral, host: RelationEndpoint): ValueFormalParameter {
        val symbol = literal.symbol
        assertEquals(KaSymbolOrigin.SOURCE, symbol.origin)
        assertEquals(null, symbol.receiverParameter)
        val parameter = symbol.valueParameters.single()
        assertTrue(parameter.isImplicitLambdaParameter)
        val actual = checkNotNull(literal.bodyExpression).statements.single()
        assertTrue(actual.isUsedAsResultOfLambda)
        val reference =
            PsiTreeUtil.collectElementsOfType(literal, org.jetbrains.kotlin.psi.KtNameReferenceExpression::class.java)
                .single { it.getReferencedName() == parameter.name.asString() }
        assertEquals(parameter, reference.references.filterIsInstance<KtReference>().single().resolveToSymbol())
        return ValueFormalParameter.fromCompilerImplicit(
                owner(literal, host),
                ValueArgumentPosition.parse(0).nativeRefined(),
                SymbolDiscoveryCandidateName.parse(parameter.name.asString()).nativeRefined(),
            )
            .nativeRefined()
    }

    private fun KaSession.owner(literal: KtFunctionLiteral, host: RelationEndpoint): ValueExecutionOwner {
        val symbol = literal.symbol
        assertEquals(KaSymbolOrigin.SOURCE, symbol.origin)
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
        val body = RelationCallableBody.Anonymous.fromCompiler(host.file, exact, signature).nativeRefined()
        return ValueExecutionOwner.fromCompiler(host, body, host.lease.identity).nativeRefined()
    }

    private fun endpoint(
        projection: IntellijK2RelationProjection,
        caller: KtNamedFunction,
        source: ValueSite,
    ): RelationEndpoint {
        val projected = projection.project(caller)
        assertTrue(projected is IntellijRelationDeclarationProjection.Projected)
        return RelationEndpoint.resolve(
                source.enclosing.lease,
                source.enclosing.scope,
                (projected as IntellijRelationDeclarationProjection.Projected).evidence,
                source.enclosing.constraints,
            )
            .nativeRefined()
    }

    private fun psi(path: String): KtFile {
        val relative = "src/" + path.removePrefix("src/main/kotlin/")
        return PsiManager.getInstance(project).findFile(checkNotNull(base.findFileByRelativePath(relative))) as KtFile
    }

    private fun functions(element: com.intellij.psi.PsiElement) =
        PsiTreeUtil.collectElementsOfType(element, KtNamedFunction::class.java)

    private fun calls(element: com.intellij.psi.PsiElement) =
        PsiTreeUtil.collectElementsOfType(element, KtCallExpression::class.java)
}

internal data class S3SeedCallbackFacts(
    val caller: RelationEndpoint,
    val callerRange: ExactDeclarationTextRange,
    val parameter: RelationOccurrence,
    val formal: ValueFormalParameter,
    val selected: RelationOccurrence,
    val wrongSupplier: ValueExecutionOwner,
    val wrongInvocation: RelationOccurrence,
)

private data class S3SeedCallbackSyntax(
    val execute: KtNamedFunction,
    val log: KtCallExpression,
    val caller: KtNamedFunction,
    val invoked: KtCallExpression,
    val supplied: KtLambdaExpression,
    val other: KtFunctionLiteral,
    val transform: KtCallExpression,
)
