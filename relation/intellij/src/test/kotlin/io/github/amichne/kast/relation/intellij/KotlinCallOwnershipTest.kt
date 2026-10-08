package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
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
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** PSI-only lexical policy proof. K1 supplies the parser; this does not claim K2 resolution. */
class KotlinCallOwnershipTest {
    @Test
    fun `exact identifier argument restores its Kotlin expression rather than its same range token`(
        @TempDir home: Path
    ) =
        withParser(home) { factory ->
            val text = "fun outer() { val first = \"x\"; persist(\"account\", first) }"
            val file = factory.createFile(text)
            val owner = file.declarations.filterIsInstance<KtNamedFunction>().single()
            val call = PsiTreeUtil.findChildOfType(owner, KtCallExpression::class.java)!!
            val expected = call.valueArguments[1].getArgumentExpression()!!
            val start = text.indexOf("first)")
            val range =
                when (val admitted = ExactDeclarationTextRange.parse(start, start + "first".length)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> error("Fixture anchor must be valid")
                }
            assertEquals(range.startInclusive, expected.textRange.startOffset)
            assertEquals(range.endExclusive, expected.textRange.endOffset)
            assertSame(expected, owner.exactValueElement(range))
        }

    @Test
    fun `nested imports and aliases have file context without a lexical declaration`(@TempDir home: Path) =
        withParser(home) { factory ->
            val file =
                factory.createFile(
                    """
                    @file:Suppress("fixture")
                    package usage
                    import model.Outer.Nested
                    import model.Outer.Nested as Renamed
                    class Consumer(val value: Renamed)
                    """
                        .trimIndent()
                )
            val annotation =
                (file.fileAnnotationList!!.annotationEntries.single().typeReference!!.typeElement
                        as org.jetbrains.kotlin.psi.KtUserType)
                    .referenceExpression!!
            assertEquals(
                io.github.amichne.kast.relation.contract.RelationReferenceContext.FILE_ANNOTATION,
                annotation.referenceContext(),
            )
            val imports = file.importDirectives
            assertEquals(
                listOf(
                    io.github.amichne.kast.relation.contract.RelationReferenceContext.IMPORT,
                    io.github.amichne.kast.relation.contract.RelationReferenceContext.ALIASED_IMPORT,
                ),
                imports.map { it.importedReference!!.referenceContext() },
            )
            imports.forEach {
                assertEquals(ContainingDeclaration.Unsupported, it.importedReference!!.nearestDeclaration())
            }
            assertEquals(1, file.declarations.size)
        }

    @Test
    fun `definition wrappers normalize to the exact Kotlin origin while Java remains supported`(@TempDir home: Path) =
        withParser(home) { factory ->
            val origin =
                factory
                    .createFile(Files.readString(Path.of("../../cli/src/test/resources/stabilization/Fixture.kt")))
                    .declarations
                    .single { (it as? org.jetbrains.kotlin.psi.KtNamedDeclaration)?.name == "CapabilityImpl" }
            val wrapper =
                java.lang.reflect.Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(org.jetbrains.kotlin.asJava.elements.KtLightElement::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getKotlinOrigin",
                        "getNavigationElement" -> origin
                        "isValid" -> true
                        else -> error("Unexpected PSI observation: ${method.name}")
                    }
                } as com.intellij.psi.PsiElement
            val navigation =
                java.lang.reflect.Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(com.intellij.psi.PsiElement::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getNavigationElement" -> wrapper
                        "isValid" -> true
                        else -> error("Unexpected PSI observation: ${method.name}")
                    }
                } as com.intellij.psi.PsiElement
            for (element in listOf(origin, wrapper, navigation)) {
                val supported = normalizeRelationDefinition(element) as IntellijRelationDefinition.Supported
                assertSame(origin, supported.declaration)
            }
            val java =
                java.lang.reflect.Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(com.intellij.psi.PsiClass::class.java),
                ) { proxy, method, _ ->
                    when (method.name) {
                        "getNavigationElement" -> proxy
                        "isValid" -> true
                        else -> error("Unexpected Java PSI observation: ${method.name}")
                    }
                } as com.intellij.psi.PsiClass
            assertSame(java, (normalizeRelationDefinition(java) as IntellijRelationDefinition.Supported).declaration)
            assertEquals(
                IntellijRelationDefinition.Unsupported,
                normalizeRelationDefinition(factory.createExpression("42")),
            )
        }

    @Test
    fun `local initializers share callable ownership and lambdas retain deferred boundaries`(@TempDir home: Path) =
        withParser(home) { factory ->
            val file = factory.createFile("fun outer(): String { val value = target(); return value }")
            assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
            val outer = file.declarations.filterIsInstance<KtNamedFunction>().single()
            val call = PsiTreeUtil.findChildOfType(outer, KtCallExpression::class.java)!!
            val observation = RecordedObservation()
            assertSame(outer, (call.nearestDeclaration(observation) as ContainingDeclaration.Found).declaration)
            val callback = factory.createFile("fun callback() = { target() }")
            assertNull(PsiTreeUtil.findChildOfType(callback, PsiErrorElement::class.java))
            val callbackCall = PsiTreeUtil.findChildOfType(callback, KtCallExpression::class.java)!!
            assertFalse(callbackCall.nearestDeclaration(observation) is ContainingDeclaration.Found)
            assertEquals(
                mapOf(
                    IntellijReadCounter.RELATION_CALL_OWNERS_FOUND to 1,
                    IntellijReadCounter.RELATION_CALL_OWNERS_UNAVAILABLE to 1,
                ),
                observation.counts,
            )
            assertEquals(setOf(IntellijReadTermination.RELATION_CALL_OWNER_UNSUPPORTED), observation.reasons)
            val deferred = callbackCall.nearestDeclaration() as ContainingDeclaration.Deferred
            assertSame(
                callback.declarations.single(),
                (deferred.enclosingDeclaration() as ContainingDeclaration.Found).declaration,
            )
            assertNestedOwner(factory)
        }

    @Test
    fun `target stage observations retain every finite decision`() {
        val observation = RecordedObservation()
        for (decision in IntellijK2TargetConfirmation.entries) {
            assertSame(decision, decision.observedBy(observation))
        }
        assertEquals(
            mapOf(
                IntellijReadCounter.RELATION_K2_CONFIRMED_TARGETS to 1,
                IntellijReadCounter.RELATION_K2_DIFFERENT_TARGETS to 1,
                IntellijReadCounter.RELATION_K2_UNAVAILABLE_TARGETS to 1,
            ),
            observation.counts,
        )
    }

    @Test
    fun `anonymous object construction belongs to enclosing callable while member body stays nested`(
        @TempDir home: Path
    ) =
        withParser(home) { factory ->
            val file =
                factory.createFile(
                    "fun outer() { val loader = object : ClassLoader() { " +
                        "override fun loadClass(name: String): Class<*> = target() }; use(loader) }"
                )
            assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
            val outer = file.declarations.single() as KtNamedFunction
            val calls = PsiTreeUtil.findChildrenOfType(file, KtCallElement::class.java)
            val member =
                PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).single { it.name == "loadClass" }
            assertSame(
                outer,
                (calls.single { it.calleeExpression?.text == "ClassLoader" }.nearestDeclaration()
                        as ContainingDeclaration.Found)
                    .declaration,
            )
            assertEquals(
                "ClassLoader",
                calls.single { it.calleeExpression?.text == "ClassLoader" }.calleeReferenceSite()?.text,
            )
            assertSame(
                member,
                (calls.single { it.calleeExpression?.text == "target" }.nearestDeclaration()
                        as ContainingDeclaration.Found)
                    .declaration,
            )
            assertSame(
                outer,
                (calls.single { it.calleeExpression?.text == "use" }.nearestDeclaration()
                        as ContainingDeclaration.Found)
                    .declaration,
            )
        }

    @Test
    fun `owner admission preserves named proof and finite unavailable observations`(@TempDir home: Path) =
        withParser(home) { factory ->
            val declaration = factory.createFile("fun named() = 1").declarations.single() as KtNamedFunction
            val lexical = ContainingDeclaration.Found(declaration)
            val observation = RecordedObservation()
            val admitted = refineCallOwnership(lexical, observation)
            assertSame(lexical, (admitted as io.github.amichne.kast.kernel.Refinement.Refined).value)
            assertEquals(
                io.github.amichne.kast.kernel.Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary),
                refineCallOwnership(ContainingDeclaration.Unsupported, observation),
            )
            assertEquals(
                mapOf(
                    IntellijReadCounter.RELATION_CALL_OWNERS_FOUND to 1,
                    IntellijReadCounter.RELATION_CALL_OWNERS_UNAVAILABLE to 1,
                ),
                observation.counts,
            )
            assertEquals(setOf(IntellijReadTermination.RELATION_CALL_OWNER_UNSUPPORTED), observation.reasons)
            assertEquals(
                io.github.amichne.kast.relation.contract.RelationLimitation.UNSUPPORTED_ITEM,
                CallOwnershipFailure.UnsupportedBoundary.limitation,
            )
            assertEquals(
                io.github.amichne.kast.relation.contract.RelationLimitation.UNRESOLVED_TARGET,
                CallOwnershipFailure.UnresolvedArgumentMapping.limitation,
            )
        }

    @Test
    fun `only proven direct inline callbacks retain the enclosing named caller`() {
        assertEquals(InlineCallbackClassification.INLINE, classifyInlineCallback(true, false, false, true))
        for (case in
            listOf(
                classifyInlineCallback(false, false, false, true),
                classifyInlineCallback(true, true, false, true),
                classifyInlineCallback(true, false, true, true),
            )) {
            assertEquals(InlineCallbackClassification.EXCLUDED, case)
        }
        assertEquals(
            InlineCallbackClassification.EXCLUDED,
            classifyInlineCallback(true, false, false, false),
        )
    }

    private fun assertNestedOwner(factory: KtPsiFactory) {
        val file = factory.createFile("fun outer() {\n fun nested() = target()\n nested()\n }")
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
        val nested = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).single { it.name == "nested" }
        val calls = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
        assertSame(
            nested,
            (calls.single { it.calleeExpression?.text == "target" }.nearestDeclaration() as ContainingDeclaration.Found)
                .declaration,
        )
        assertSame(
            file.declarations.single(),
            (calls.single { it.calleeExpression?.text == "nested" }.nearestDeclaration() as ContainingDeclaration.Found)
                .declaration,
        )
    }

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
        org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    )
    internal fun withParser(home: Path, assertion: (KtPsiFactory) -> Unit) {
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

    private class RecordedObservation : IntellijReadObservation {
        val counts = mutableMapOf<IntellijReadCounter, Int>()
        val reasons = mutableSetOf<IntellijReadTermination>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            counts[counter] = counts.getOrDefault(counter, 0) + amount
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            reasons += reason
        }
    }
}
