@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile

/** K2 supplies the expected call and artifact independently of receiver admission. */
internal class NativePacketReviewedReceiverSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun expectation(case: ReceiverAdmissionCase): ReceiverAdmissionExpectation =
        ReadAction.compute<ReceiverAdmissionExpectation, RuntimeException> {
            val path = if (case == ReceiverAdmissionCase.REVIEWED) FEATURE else V6
            val file = checkNotNull(base.findFileByRelativePath("src/" + path.removePrefix("src/main/kotlin/")))
            val psi = PsiManager.getInstance(project).findFile(file) as KtFile
            val text =
                if (case == ReceiverAdmissionCase.REVIEWED) "response.links.firstOrNull()"
                else "wireResponse.links.first()"
            val qualified =
                PsiTreeUtil.collectElementsOfType(psi, KtDotQualifiedExpression::class.java).single { it.text == text }
            val original =
                if (case == ReceiverAdmissionCase.REVIEWED)
                    NativePacketDestructuringProbeSource(project, base, fixture).expectation()
                else null
            val host =
                original?.enclosing ?: fixture.callableDeclaration(PacketDeclaration(V6, "BackendClient", "fetch"))
            val owner = original?.executionOwner ?: ValueExecutionOwner.declaration(RelationEndpoint.subject(host))
            if (case == ReceiverAdmissionCase.REVIEWED) {
                assertEquals(exact(RECEIVER_START, RECEIVER_END), range(qualified.receiverExpression))
                assertEquals(exact(RECEIVER_START, CALL_END), range(qualified))
                assertEquals(exact(SEQUENTIAL_START, SEQUENTIAL_END), owner.range)
            }
            independentBinding(case = case, qualified = qualified, host = host, owner = owner)
        }

    private fun independentBinding(
        case: ReceiverAdmissionCase,
        qualified: KtDotQualifiedExpression,
        host: SymbolSelector,
        owner: ValueExecutionOwner,
    ): ReceiverAdmissionExpectation {
        val call = qualified.selectorExpression as KtCallExpression
        return IntellijReadObservation.None.observedAnalyze(call) {
            val resolved = checkNotNull(call.resolveCall()) { "K2 prerequisite: unresolved library call" }
            val symbol = resolved.signature.symbol
            assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
            val extension = resolved.extensionReceiver as? KaExplicitReceiverValue
            assertNotNull("K2 prerequisite: explicit extension receiver is absent", extension)
            assertEquals(
                qualified.receiverExpression.originalElement,
                checkNotNull(extension).expression.originalElement,
            )
            assertNull(resolved.dispatchReceiver)
            val projected = symbol.compilerProjection(this)
            assertTrue(projected is IntellijCompilerProjectionResult.Projected)
            val binding = (projected as IntellijCompilerProjectionResult.Projected).projection
            val expected = expectedSignature(case)
            assertEquals(expected, binding.signature)
            val declaration = checkNotNull(symbol.psi) as PsiNamedElement
            val endpoint = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(declaration)
            assertTrue(endpoint is IntellijRelationDeclarationProjection.Projected)
            val evidence = (endpoint as IntellijRelationDeclarationProjection.Projected).evidence
            assertTrue(evidence.file is SymbolDiscoveryFileIdentity.External)
            assertArtifact(symbol.containingModule, declaration, evidence.file)
            ReceiverAdmissionExpectation(
                host = host,
                owner = owner,
                receiver = range(qualified.receiverExpression),
                call = range(qualified),
                identity = binding.identity,
                signature = expected,
                file = evidence.file,
            )
        }
    }

    private fun expectedSignature(case: ReceiverAdmissionCase): CanonicalCompilerSignature =
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity =
                    if (case == ReceiverAdmissionCase.REVIEWED) "kotlin.collections.firstOrNull"
                    else "kotlin.collections.first",
                rawReceiverType = "kotlin/collections/List<T>",
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = emptyList(),
                rawTypeParameterCount = 1,
            )
            .nativeRefined()

    private fun assertArtifact(
        module: org.jetbrains.kotlin.analysis.api.projectStructure.KaModule,
        declaration: PsiNamedElement,
        file: SymbolDiscoveryFileIdentity,
    ) {
        val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))
        assertEquals("0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e", digest)
        JarFile(jar.toFile()).use { archive ->
            assertEquals(
                "2.3.20-release-208",
                archive.manifest.mainAttributes.getValue("Implementation-Version"),
            )
        }
        val artifact = packetLibraryArtifactAffiliation(module, declaration, jar.toString())
        assertTrue(artifact.artifactRoot in artifact.moduleRoots)
        assertTrue(artifact.containsDeclaration)
        assertEquals(
            artifact.declarationUrl,
            (file as SymbolDiscoveryFileIdentity.External).url.value,
        )
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        const val V6 = "src/main/kotlin/fixture/variants/v6/V6.kt"
        const val RECEIVER_START = 939
        const val RECEIVER_END = 953
        const val CALL_END = 967
        const val SEQUENTIAL_START = 677
        const val SEQUENTIAL_END = 1063
    }
}

internal enum class ReceiverAdmissionCase {
    REVIEWED,
    UNREVIEWED,
}

internal data class ReceiverAdmissionExpectation(
    val host: SymbolSelector,
    val owner: ValueExecutionOwner,
    val receiver: ExactDeclarationTextRange,
    val call: ExactDeclarationTextRange,
    val identity: CompilerSymbolIdentity,
    val signature: CanonicalCompilerSignature,
    val file: SymbolDiscoveryFileIdentity,
)
