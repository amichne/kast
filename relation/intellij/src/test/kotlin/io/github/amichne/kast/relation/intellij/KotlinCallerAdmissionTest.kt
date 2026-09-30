package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real Kotlin PSI and native reference shapes; these checks do not establish K2 target resolution. */
class KotlinCallerAdmissionTest {
    @Test
    fun `implicit and explicit invoke references are both admitted as call sites`(@TempDir home: Path) =
        withParser(home) { factory ->
            val file =
                factory.createFile(
                    """
                    package fixture.calls
                    fun interface Fetcher { operator fun invoke(): String }
                    fun implicitInvoke(fetcher: Fetcher): String = fetcher()
                    fun explicitInvoke(fetcher: Fetcher): String = fetcher.invoke()
                    """
                        .trimIndent()
                )
            assertParsed(file)
            val implicitCall = callsTo(file, "fetcher").single()
            val implicitReference = nativeInvokeReference(implicitCall)
            assertSame(implicitCall, implicitReference.element)
            assertEquals(TextRange(0, 9), implicitReference.rangeInElement)
            assertEquals(listOf(TextRange(7, 9)), implicitReference.ranges)
            val explicitCall = callsTo(file, "invoke").single()
            val explicitReference =
                nativeReference(
                    assertInstanceOf(KtSimpleNameExpression::class.java, explicitCall.calleeReferenceSite())
                )
            val request = RelationReadTest().request(RelationMeaning.Callers)
            val callers =
                IntellijRelationPlan.References(
                    namedFunction(file, "invoke"),
                    request.subject,
                    IntellijReferenceConfirmationPlan.ExactSymbol(IntellijExactReferenceShape.CALL),
                )
            val observation = RecordedObservation()
            for (reference in listOf(implicitReference, explicitReference)) {
                val admitted =
                    assertInstanceOf(
                        IntellijRelationReferenceAdmission.Admitted.ExactSymbol::class.java,
                        callers.admit(reference, observation),
                    )
                assertSame(reference, admitted.reference)
                assertSame(request.subject, admitted.endpoint)
            }
            assertEquals(mapOf(IntellijReadCounter.RELATION_REFERENCE_SHAPES_ADMITTED to 2), observation.counts)
            assertNamedOwner(implicitCall, namedFunction(file, "implicitInvoke"))
            assertNamedOwner(explicitCall, namedFunction(file, "explicitInvoke"))
        }

    @Test
    fun `private generic serializer calls retain local initializer and expression body owners`(@TempDir home: Path) =
        withParser(home) { factory ->
            val file =
                factory.createFile(
                    """
                    package fixture.caching
                    class JsonSerializer {
                        fun <T> fromJson(value: T): String {
                            val type = parameterizedTypeForSuccessClass<T>(value)
                            return type
                        }
                        fun <T> toJson(value: T): String = parameterizedTypeForSuccessClass(value)
                        private fun <T> parameterizedTypeForSuccessClass(value: T): String = "type"
                    }
                    """
                        .trimIndent()
                )
            assertParsed(file)
            val target = namedFunction(file, "parameterizedTypeForSuccessClass")
            val calls = callsTo(file, "parameterizedTypeForSuccessClass")
            assertEquals(2, calls.size)
            assertEquals(listOf(1, 0), calls.map { it.typeArguments.size })
            for ((call, expectedOwner) in calls.zip(listOf("fromJson", "toJson"))) {
                assertCallAdmission(target, call)
                assertNamedOwner(call, namedFunction(file, expectedOwner))
            }
        }

    @Test
    fun `private cache calls inside lambdas retain deferred ownership while direct calls retain named ownership`(
        @TempDir home: Path
    ) =
        withParser(home) { factory ->
            val file =
                factory.createFile(
                    """
                    package fixture.caching
                    class CacheManager {
                        fun invalidateCachedResponse() = run { invalidateCrossCloudCache() }
                        fun storedCallback(): () -> Unit {
                            val callback = { invalidateCrossCloudCache() }
                            return callback
                        }
                        fun ordinaryCallback() = consume { invalidateCrossCloudCache() }
                        fun directCall() { invalidateCrossCloudCache() }
                        private fun invalidateCrossCloudCache() {}
                        private fun consume(block: () -> Unit) = block()
                    }
                    """
                        .trimIndent()
                )
            assertParsed(file)
            val target = namedFunction(file, "invalidateCrossCloudCache")
            val calls = callsTo(file, "invalidateCrossCloudCache")
            assertEquals(4, calls.size)
            for ((call, expectedOwner) in
                calls.take(3).zip(listOf("invalidateCachedResponse", "storedCallback", "ordinaryCallback"))) {
                assertCallAdmission(target, call)
                val deferred = assertInstanceOf(ContainingDeclaration.Deferred::class.java, call.nearestDeclaration())
                val enclosing =
                    assertInstanceOf(ContainingDeclaration.Found::class.java, deferred.enclosingDeclaration())
                assertSame(namedFunction(file, expectedOwner), enclosing.declaration)
            }
            assertCallAdmission(target, calls.last())
            assertNamedOwner(calls.last(), namedFunction(file, "directCall"))
        }

    @Test
    fun `interface calls in production and test bodies retain their own named callers`(@TempDir home: Path) =
        withParser(home) { factory ->
            val production =
                factory.createFile(
                    "OutageConfigProvider.kt",
                    """
                    package fixture.outages
                    interface OutageConfigProvider { fun getPreLoginOutageInfo(): String }
                    class OutageConfigProviderImpl : OutageConfigProvider {
                        override fun getPreLoginOutageInfo(): String = "available"
                    }
                    fun load(provider: OutageConfigProvider): String {
                        val outage = provider.getPreLoginOutageInfo()
                        return outage
                    }
                    """
                        .trimIndent(),
                )
            val test =
                factory.createFile(
                    "OutageConfigProviderTest.kt",
                    """
                    package fixture.outages
                    fun verifiesPreLoginOutageInfo(provider: OutageConfigProvider): Boolean =
                        provider.getPreLoginOutageInfo() == "available"
                    """
                        .trimIndent(),
                )
            assertParsed(production)
            assertParsed(test)
            val target =
                PsiTreeUtil.findChildrenOfType(production, KtNamedFunction::class.java).single {
                    it.name == "getPreLoginOutageInfo" && it.bodyExpression == null
                }
            for ((file, expectedOwner) in listOf(production to "load", test to "verifiesPreLoginOutageInfo")) {
                val call = callsTo(file, "getPreLoginOutageInfo").single()
                assertCallAdmission(target, call)
                assertNamedOwner(call, namedFunction(file, expectedOwner))
            }
        }

    @Test
    fun `private callable reference remains a reference without manufacturing a direct caller`(@TempDir home: Path) =
        withParser(home) { factory ->
            val file =
                factory.createFile(
                    """
                    package fixture.caching
                    class CacheManager {
                        fun callback() = consume(::invalidateCrossCloudCache)
                        private fun invalidateCrossCloudCache() {}
                        private fun consume(block: () -> Unit) = block()
                    }
                    """
                        .trimIndent()
                )
            assertParsed(file)
            val target = namedFunction(file, "invalidateCrossCloudCache")
            val expression =
                PsiTreeUtil.findChildrenOfType(file, KtCallableReferenceExpression::class.java)
                    .single()
                    .callableReference
            val reference = nativeReference(expression)
            val request = RelationReadTest().request(RelationMeaning.Callers)
            val callers =
                IntellijRelationPlan.References(
                    target,
                    request.subject,
                    IntellijReferenceConfirmationPlan.ExactSymbol(IntellijExactReferenceShape.CALL),
                )
            val observation = RecordedObservation()
            assertEquals(IntellijRelationReferenceAdmission.Skipped, callers.admit(reference, observation))
            assertEquals(mapOf(IntellijReadCounter.RELATION_REFERENCE_SHAPES_SKIPPED to 1), observation.counts)
            val references =
                callers.copy(
                    confirmation = IntellijReferenceConfirmationPlan.ExactSymbol(IntellijExactReferenceShape.ANY)
                )
            val admitted =
                assertInstanceOf(
                    IntellijRelationReferenceAdmission.Admitted.ExactSymbol::class.java,
                    references.admit(reference, observation),
                )
            assertSame(reference, admitted.reference)
            assertSame(request.subject, admitted.endpoint)
            assertEquals(
                mapOf(
                    IntellijReadCounter.RELATION_REFERENCE_SHAPES_ADMITTED to 1,
                    IntellijReadCounter.RELATION_REFERENCE_SHAPES_SKIPPED to 1,
                ),
                observation.counts,
            )
        }

    private fun assertCallAdmission(target: KtNamedFunction, call: KtCallExpression) {
        val expression = assertInstanceOf(KtSimpleNameExpression::class.java, call.calleeReferenceSite())
        val reference = nativeReference(expression)
        // The existing detached endpoint supplies selection data only; target identity is not resolved in this test.
        val request = RelationReadTest().request(RelationMeaning.Callers)
        val plan =
            IntellijRelationPlan.References(
                target,
                request.subject,
                IntellijReferenceConfirmationPlan.ExactSymbol(IntellijExactReferenceShape.CALL),
            )
        val observation = RecordedObservation()
        val admitted =
            assertInstanceOf(
                IntellijRelationReferenceAdmission.Admitted.ExactSymbol::class.java,
                plan.admit(reference, observation),
            )
        assertSame(reference, admitted.reference)
        assertSame(request.subject, admitted.endpoint)
        assertEquals(mapOf(IntellijReadCounter.RELATION_REFERENCE_SHAPES_ADMITTED to 1), observation.counts)
    }

    private fun assertNamedOwner(call: KtCallExpression, expected: KtNamedFunction) {
        val lexical = assertInstanceOf(ContainingDeclaration.Found::class.java, call.nearestDeclaration())
        assertSame(expected, lexical.declaration)
        val refined =
            assertInstanceOf(
                Refinement.Refined::class.java,
                refineCallOwnership(lexical, IntellijReadObservation.None),
            )
        assertSame(lexical, refined.value)
    }

    /** IDEA owns this Kotlin-internal constructor; no resolution or reference provider is substituted. */
    private fun nativeReference(expression: KtSimpleNameExpression): KtReference =
        Class.forName("org.jetbrains.kotlin.idea.references.impl.KaBaseSimpleNameReference")
            .getConstructor(KtSimpleNameExpression::class.java, Boolean::class.javaPrimitiveType)
            .newInstance(expression, true) as KtReference

    private fun nativeInvokeReference(call: KtCallExpression): KtInvokeFunctionReference =
        Class.forName("org.jetbrains.kotlin.idea.references.impl.KaBaseInvokeFunctionReference")
            .getConstructor(KtCallExpression::class.java)
            .newInstance(call) as KtInvokeFunctionReference

    private fun assertParsed(file: KtFile) {
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
    }

    private fun namedFunction(file: KtFile, name: String): KtNamedFunction =
        PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).single { it.name == name }

    private fun callsTo(file: KtFile, name: String): List<KtCallExpression> =
        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
            .filter { (it.calleeExpression as? KtSimpleNameExpression)?.getReferencedName() == name }
            .sortedBy { it.textRange.startOffset }

    private class RecordedObservation : IntellijReadObservation {
        val counts = mutableMapOf<IntellijReadCounter, Int>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            counts[counter] = counts.getOrDefault(counter, 0) + amount
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            error("Reference shape admission has no termination outcome: $reason")
        }
    }

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
        org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    )
    private fun withParser(home: Path, assertion: (KtPsiFactory) -> Unit) {
        val properties =
            listOf("idea.home.path", "idea.config.path", "idea.system.path").associateWith(System::getProperty)
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("bin/idea.properties"), "")
        System.setProperty("idea.home.path", home.toString())
        System.setProperty("idea.config.path", home.resolve("config").toString())
        System.setProperty("idea.system.path", home.resolve("system").toString())
        val disposable = Disposer.newDisposable()
        try {
            val environment =
                KotlinCoreEnvironment.createForTests(
                    disposable,
                    CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )
            assertion(KtPsiFactory(environment.project))
        } finally {
            ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) }
            properties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }
}
