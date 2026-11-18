@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

class NativeArrayCallbackTest : NativeDependencyCallbackFixture() {
    override val fixtureSdkName = "array-callback-case-jdk"

    override fun runInDispatchThread() = false

    fun testPrimitiveArrayIterationUsesTheReviewedAction() {
        val jar = pinnedLibrary()
        val fixture =
            fixture(
                expression = "seed(1)",
                sources = false,
                helper =
                    "fun post(i: Int) = proof.target()\nfun seed(vararg counts: Int) = counts.forEach { post(it) }\n",
                libraryJar = jar,
            )
        ReadAction.run<RuntimeException> {
            val file = psiManager.findFile(fixture.callerFile) as KtFile
            val call =
                PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java).single {
                    it.calleeExpression?.text == "forEach"
                }
            IntellijReadObservation.None.observedAnalyze(call) {
                val resolved = checkNotNull(call.resolveCall())
                val target = resolved.signature.symbol as KaNamedFunctionSymbol
                assertEquals("kotlin.collections.forEach", target.callableId?.asSingleFqName()?.asString())
                val receiver = target.receiverParameter?.returnType as KaClassType
                assertEquals("kotlin.IntArray", receiver.classId.asSingleFqName().asString())
                assertEquals("action", resolved.valueArgumentMapping.values.single().symbol.name.asString())
                assertTrue(target.contractEffects.isEmpty())
                val external = checkNotNull(target.psi?.containingFile?.virtualFile)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(external.contentsToByteArray())
                val hex = java.util.HexFormat.of().formatHex(digest)
                assertEquals("ArraysKt.class", external.name)
                assertEquals("872436d952bd6216482465e7213427dde8afe9f7d57cdfd3093bb1fcaf3341eb", hex)
            }
            val flow = read(fixture, ownerName = "seed")
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
            val binding = (flow.binding as CallbackBindingEvidence.DependencyContract).binding
            assertEquals("REVIEWED_KOTLIN_2_4_10_INT_ARRAY_FOR_EACH", binding.provenance.name)
            assertEquals("ZERO_OR_MORE", binding.invocationKind.name)
            assertEquals(0, binding.position.value)
            assertTrue(flow.invocations.isEmpty())
        }
    }

    fun testChangedArchiveCannotSupplyTheReviewedProof() {
        val changed = Files.createTempFile("array-iteration-changed-", ".jar")
        try {
            Files.copy(pinnedLibrary(), changed, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            // The ZIP reader accepts trailing data. Every class remains byte-identical.
            Files.write(changed, byteArrayOf(1), StandardOpenOption.APPEND)
            val fixture = fixture("intArrayOf(1).forEach { proof.target() }", false, libraryJar = changed)
            ReadAction.run<RuntimeException> {
                val flow = read(fixture)
                assertEquals(setOf(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN), flow.obligations)
                assertEquals(CallbackInvocationScan.INCOMPLETE, flow.scan)
            }
        } finally {
            Files.deleteIfExists(changed)
        }
    }

    fun testDeferredUserDefinedForEachCannotSupplyTheReviewedProof() {
        val fixture =
            fixture(
                expression = "counts.forEach { proof.target() }",
                sources = false,
                helper =
                    "val counts = intArrayOf(1)\nvar later: (() -> Unit)? = null\n" +
                        "fun IntArray.forEach(action: (Int) -> Unit) { later = { action(1) } }\n",
                libraryJar = pinnedLibrary(),
            )
        ReadAction.run<RuntimeException> {
            val flow = read(fixture)
            assertFalse(flow.binding is CallbackBindingEvidence.DependencyContract)
        }
    }

    private fun pinnedLibrary(): Path =
        Path.of(checkNotNull(javaClass.getResource("/reviewed-libraries/kotlin-stdlib-2.4.10.jar")).toURI())
}
