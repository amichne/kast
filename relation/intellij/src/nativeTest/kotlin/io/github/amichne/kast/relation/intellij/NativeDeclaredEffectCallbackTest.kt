@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractCallsInPlaceContractEffectDeclaration
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** R1 reads real compiled contracts. Authored source fixes the overload, parameter, owner, and effect. */
class NativeDeclaredEffectCallbackTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "declared-effects-jdk"

    override fun runInDispatchThread() = false

    fun testExactlyOnceLambdaFunction() = assertCase("exactlyOnceLambdaFunction", "EXACTLY_ONCE")

    fun testExactlyOnceLambdaConstructor() = assertCase("exactlyOnceLambdaConstructor", "EXACTLY_ONCE")

    fun testExactlyOnceAnonymousFunction() = assertCase("exactlyOnceAnonymousFunction", "EXACTLY_ONCE")

    fun testExactlyOnceAnonymousConstructor() = assertCase("exactlyOnceAnonymousConstructor", "EXACTLY_ONCE")

    fun testAtMostOnceLambdaFunction() = assertCase("atMostOnceLambdaFunction", "AT_MOST_ONCE")

    fun testAtMostOnceLambdaConstructor() = assertCase("atMostOnceLambdaConstructor", "AT_MOST_ONCE")

    fun testAtMostOnceAnonymousFunction() = assertCase("atMostOnceAnonymousFunction", "AT_MOST_ONCE")

    fun testAtMostOnceAnonymousConstructor() = assertCase("atMostOnceAnonymousConstructor", "AT_MOST_ONCE")

    fun testAtLeastOnceLambdaFunction() = assertCase("atLeastOnceLambdaFunction", "AT_LEAST_ONCE")

    fun testAtLeastOnceLambdaConstructor() = assertCase("atLeastOnceLambdaConstructor", "AT_LEAST_ONCE")

    fun testAtLeastOnceAnonymousFunction() = assertCase("atLeastOnceAnonymousFunction", "AT_LEAST_ONCE")

    fun testAtLeastOnceAnonymousConstructor() = assertCase("atLeastOnceAnonymousConstructor", "AT_LEAST_ONCE")

    fun testUnknownLambdaFunction() = assertCase("unknownLambdaFunction", "UNKNOWN")

    fun testUnknownLambdaConstructor() = assertCase("unknownLambdaConstructor", "UNKNOWN")

    fun testUnknownAnonymousFunction() = assertCase("unknownAnonymousFunction", "UNKNOWN")

    fun testUnknownAnonymousConstructor() = assertCase("unknownAnonymousConstructor", "UNKNOWN")

    fun testAllAuthoredInputsCompileWithThePinnedCompiler() {
        DeclaredEffectArchive.compile().use { it.compileConsumers() }
    }

    fun testAbsentEffectDoesNotAcquireUnknown() = assertMissingEffect("absentLambdaFunction")

    fun testOtherFormalEffectDoesNotAcquireTheDeclaredEffect() = assertMissingEffect("otherParameterLambdaFunction")

    fun testSameNameStringOverloadDoesNotAcquireTheIntContract() = assertMissingEffect("sameNameStringLambdaFunction")

    private fun assertCase(ownerName: String, effect: String) {
        DeclaredEffectArchive.compile().use { archive ->
            val fixture = fixture(archive)
            ReadAction.run<RuntimeException> {
                val owner = owner(fixture, ownerName)
                val literal = literal(owner)
                assertCompilerContract(owner, literal, effect)
                val flow = read(fixture, owner, literal)
                assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
                assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
                assertTrue(
                    "Expected $effect binary contract, received ${flow.binding}",
                    flow.binding is CallbackBindingEvidence.DependencyContract,
                )
                val binding = (flow.binding as CallbackBindingEvidence.DependencyContract).binding
                assertEquals(effect, binding.invocationKind.name)
                assertEquals(1, binding.position.value)
                assertEquals("KOTLIN_BINARY_CONTRACT", binding.provenance.name)
                assertEquals(archive.classDigest, binding.classDigest.value)
                assertEquals(fixture.request.subject.lease.identity, binding.basis)
                val dependency = binding.target.signature as CanonicalCompilerSignature.Function
                assertEquals("effects.${api(ownerName)}", dependency.qualifiedIdentity.value)
                assertEquals("kotlin/Int", dependency.valueParameters.first().value)
                assertEquals(2, dependency.valueParameters.size)
                val supplier = binding.owner as RelationCallableBody.Named
                assertEquals(
                    "consumer.$ownerName",
                    (supplier.evidence.signature as CanonicalCompilerSignature.Function).qualifiedIdentity.value,
                )
                assertEquals(literal.textRange.startOffset, flow.body.range.startInclusive)
                assertEquals(literal.textRange.endOffset, flow.body.range.endExclusive)
                val dependencyFile = binding.target.file as SymbolDiscoveryFileIdentity.External
                assertEquals("jar://${archive.jar}!/effects/DependencyEffectsKt.class", dependencyFile.url.value)
                assertTrue(flow.invocations.isEmpty())
                println("R1 case=$ownerName effect=$effect classSha256=${archive.classDigest}")
                println("R1 case=$ownerName obligations=${flow.obligations}")
            }
        }
    }

    private fun assertMissingEffect(ownerName: String) {
        DeclaredEffectArchive.compile().use { archive ->
            val fixture = fixture(archive)
            ReadAction.run<RuntimeException> {
                val owner = owner(fixture, ownerName)
                val flow = read(fixture, owner, literal(owner))
                assertEquals(setOf(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN), flow.obligations)
                assertEquals(CallbackInvocationScan.INCOMPLETE, flow.scan)
                assertTrue(flow.binding is CallbackBindingEvidence.Unavailable)
                assertTrue(flow.invocations.isEmpty())
            }
        }
    }

    private fun assertCompilerContract(owner: KtNamedFunction, literal: KtFunction, effect: String) {
        val call = PsiTreeUtil.collectElementsOfType(owner, KtCallExpression::class.java).first()
        val expression = if (literal is org.jetbrains.kotlin.psi.KtFunctionLiteral) literal.parent else literal
        analyze(call) {
            val resolved = checkNotNull(call.resolveCall())
            val dependency = resolved.signature.symbol as KaNamedFunctionSymbol
            assertEquals(KaSymbolOrigin.LIBRARY, dependency.origin)
            assertEquals(
                "effects.${api(checkNotNull(owner.name))}",
                dependency.callableId?.asSingleFqName()?.asString(),
            )
            assertEquals(listOf("marker", "block"), dependency.valueParameters.map { it.name.asString() })
            val markerType = dependency.valueParameters[0].returnType as KaClassType
            assertEquals("kotlin.Int", markerType.classId.asSingleFqName().asString())
            val parameter = checkNotNull(resolved.valueArgumentMapping[expression]?.symbol)
            assertEquals(dependency.valueParameters[1], parameter)
            val declared =
                dependency.contractEffects.filterIsInstance<KaContractCallsInPlaceContractEffectDeclaration>()
            assertEquals(1, declared.size)
            assertEquals(parameter, declared.single().valueParameterReference.symbol)
            assertEquals(effect, declared.single().invocationKind.name)
        }
    }

    private fun fixture(archive: DeclaredEffectArchive): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            ModuleRootModificationUtil.updateModel(module) { model ->
                val library = model.moduleLibraryTable.createLibrary("declared-effects-binary")
                val roots = library.modifiableModel
                roots.addRoot("jar://${archive.jar}!/", OrderRootType.CLASSES)
                roots.addRoot(
                    "jar://${Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")}!/",
                    OrderRootType.CLASSES,
                )
                roots.commit()
            }
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(DeclaredEffectArchive.resource("CallerCombinations.kt"))
                fixture.target.containingFile.virtualFile.setBinaryContent(DeclaredEffectArchive.resource("Targets.kt"))
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }

    private fun owner(fixture: NativeReferenceFixture, name: String): KtNamedFunction =
        (psiManager.findFile(fixture.callerFile) as KtFile).declarations.filterIsInstance<KtNamedFunction>().single {
            it.name == name
        }

    private fun literal(owner: KtNamedFunction): KtFunction {
        val lambda = PsiTreeUtil.findChildOfType(owner, KtLambdaExpression::class.java)
        if (lambda != null) return lambda.functionLiteral
        return PsiTreeUtil.collectElementsOfType(owner, KtNamedFunction::class.java).single { it.name == null }
    }

    private fun read(
        fixture: NativeReferenceFixture,
        owner: KtNamedFunction,
        literal: KtFunction,
    ): io.github.amichne.kast.relation.contract.CallbackInvocationFlow {
        val projection = IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot)
        val lexicalOwner = (projection.project(owner) as IntellijRelationDeclarationProjection.Projected).evidence
        val observation = IntellijReadObservation.None
        val collector = IntellijRelationCollector(fixture.request)
        val result =
            readCallbackInvocationFlow(
                boundary = literal,
                lexicalOwner = lexicalOwner,
                scope = fixture.scope(observation),
                projection = projection,
                admitWork = collector::admitCallbackWork,
                summaries = CallbackParameterSummaries(fixture.request.budget, observation),
                callbackExpiry = collector::callbackExpiry,
            )
        assertTrue("Expected callback observation, received $result", result is CallbackInvocationFlowRead.Observed)
        return (result as CallbackInvocationFlowRead.Observed).flow
    }

    private fun api(owner: String): String = owner.substringBefore("Lambda").substringBefore("Anonymous")
}
