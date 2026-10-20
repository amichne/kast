@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.LibraryArtifactDigest
import io.github.amichne.kast.relation.contract.LibraryArtifactObservation
import io.github.amichne.kast.relation.contract.LibraryArtifactVersion
import io.github.amichne.kast.relation.contract.LibraryModelDeclarationOrigin
import io.github.amichne.kast.relation.contract.LibraryModelModuleName
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Actual source and K2 facts determine expected bodies, formals and calls without a model application factory. */
internal class NativePacketLetCallbackFacts(private val project: Project, private val base: VirtualFile) {
    fun describe(receiver: ValueSite): LetCallbackFacts =
        ReadAction.compute<LetCallbackFacts, RuntimeException> {
            val call = (receiver.role as ValueRole.Receiver).call
            val qualified =
                PsiTreeUtil.collectElementsOfType(psi(receiver), KtQualifiedExpression::class.java).single { qualified
                    ->
                    range(qualified) == call.range
                }
            val selected = qualified.selectorExpression as KtCallExpression
            val supplied = checkNotNull(selected.lambdaArguments.single().getLambdaExpression())
            val literal = supplied.functionLiteral
            IntellijReadObservation.None.observedAnalyze(selected) {
                val resolved = checkNotNull(selected.resolveCall())
                assertEquals(KaSymbolOrigin.LIBRARY, resolved.signature.symbol.origin)
                assertNull(resolved.dispatchReceiver)
                val extension = resolved.extensionReceiver as KaExplicitReceiverValue
                assertEquals(qualified.receiverExpression.originalElement, extension.expression.originalElement)
                assertEquals(receiver.range, range(extension.expression))
                assertEquals(
                    resolved.signature.symbol.valueParameters.single(),
                    resolved.valueArgumentMapping[supplied]?.symbol,
                )
                val projected = resolved.signature.symbol.compilerProjection(this)
                assertTrue(projected is IntellijCompilerProjectionResult.Projected)
                val declaration = (projected as IntellijCompilerProjectionResult.Projected).projection
                assertEquals(expectedLet(), declaration.signature)
                assertEquals(call.callable.compilerIdentity, declaration.identity)
                val artifact =
                    artifact(resolved.signature.symbol.containingModule, resolved.signature.symbol.psi, receiver)
                val binding =
                    ReviewedLibraryBinding.fromCompiler(call, ReviewedValueModel.Let, artifact).nativeRefined()
                describeBody(receiver = receiver, literal = literal, binding = binding)
            }
        }

    private fun KaSession.describeBody(
        receiver: ValueSite,
        literal: KtFunctionLiteral,
        binding: ReviewedLibraryBinding,
    ): LetCallbackFacts {
        val symbol = literal.symbol
        assertNull(symbol.receiverParameter)
        val actual = symbol.valueParameters.single()
        assertTrue(actual.isImplicitLambdaParameter)
        assertEquals("it", actual.name.asString())
        val references =
            PsiTreeUtil.collectElementsOfType(literal, KtNameReferenceExpression::class.java).filter { reference ->
                reference.references.filterIsInstance<KtReference>().single().resolveToSymbol() == actual
            }
        val result = checkNotNull(literal.bodyExpression).statements.single()
        assertTrue(result.isUsedAsResultOfLambda)
        val owner = owner(receiver, literal)
        val parameter =
            ValueFormalParameter.fromCompilerImplicit(
                    owner,
                    ValueArgumentPosition.parse(0).nativeRefined(),
                    SymbolDiscoveryCandidateName.parse("it").nativeRefined(),
                )
                .nativeRefined()
        println(
            "LET_CALLBACK_K2 call=${binding.invocation.range} receiver=${receiver.range} slot=EXTENSION " +
                "dispatch=NONE body=${range(literal)} formal=IMPLICIT_PARAMETER_0 " +
                "references=${references.map(::range)} " +
                "normalResult=${range(result)}"
        )
        return LetCallbackFacts(
            receiver = receiver,
            binding = binding,
            argument = RelationOccurrence.fromSource(receiver.enclosing.file, range(literal)),
            formal = parameter,
            result = range(result),
            references = references.map(::range),
        )
    }

    private fun KaSession.owner(receiver: ValueSite, literal: KtFunctionLiteral): ValueExecutionOwner {
        val symbol = literal.symbol
        val exact = range(literal)
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity =
                        RelationCallableBody.Anonymous.sourceIdentity(receiver.enclosing.file, exact),
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = symbol.valueParameters.map { it.returnType.toString() },
                    rawTypeParameterCount = 0,
                )
                .nativeRefined() as CanonicalCompilerSignature.Function
        val body =
            RelationCallableBody.Anonymous.fromCompiler(receiver.enclosing.file, exact, signature).nativeRefined()
        return ValueExecutionOwner.fromCompiler(receiver.enclosing, body, receiver.basis).nativeRefined()
    }

    private fun artifact(
        actual: org.jetbrains.kotlin.analysis.api.projectStructure.KaModule,
        declaration: com.intellij.psi.PsiElement?,
        receiver: ValueSite,
    ): LibraryArtifactObservation {
        val module =
            when (actual) {
                is KaLibraryModule -> actual
                is KaLibrarySourceModule -> actual.binaryLibrary
                else -> error("The actual let target must belong to a library module")
            }
        val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))
        val version = JarFile(jar.toFile()).use { it.manifest.mainAttributes.getValue("Implementation-Version") }
        assertEquals(BINARY_SHA, hash)
        assertEquals(VERSION, version)
        val affiliation =
            packetLibraryArtifactAffiliation(module, declaration, jar.toAbsolutePath().normalize().toString())
        assertTrue(affiliation.artifactRoot in affiliation.moduleRoots)
        assertTrue(affiliation.containsDeclaration)
        return LibraryArtifactObservation(
            basis = receiver.basis,
            root = DetachedVirtualFileUrl.parse(affiliation.artifactRoot).nativeRefined(),
            moduleName = LibraryModelModuleName.parse(module.libraryName).nativeRefined(),
            binaryDigest = LibraryArtifactDigest.parse(hash).nativeRefined(),
            version = LibraryArtifactVersion.parse(version).nativeRefined(),
            origin = LibraryModelDeclarationOrigin.LIBRARY,
        )
    }

    private fun psi(source: ValueSite): KtFile {
        val path =
            (source.enclosing.file as io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.Workspace)
                .path
                .value
        val relative = base.toNioPath().relativize(Path.of(path)).toString()
        val file = checkNotNull(base.findFileByRelativePath(relative))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    private fun range(element: com.intellij.psi.PsiElement) =
        ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset).nativeRefined()

    private fun expectedLet() =
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity = "kotlin.let",
                rawReceiverType = "T",
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf("(T)->R"),
                rawTypeParameterCount = LET_TYPE_PARAMETERS,
            )
            .nativeRefined()

    private companion object {
        const val LET_TYPE_PARAMETERS = 2
        const val VERSION = "2.3.20-release-208"
        const val BINARY_SHA = "0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"
    }
}

internal data class LetCallbackFacts(
    val receiver: ValueSite,
    val binding: ReviewedLibraryBinding,
    val argument: RelationOccurrence,
    val formal: ValueFormalParameter,
    val result: ExactDeclarationTextRange,
    val references: List<ExactDeclarationTextRange>,
)
