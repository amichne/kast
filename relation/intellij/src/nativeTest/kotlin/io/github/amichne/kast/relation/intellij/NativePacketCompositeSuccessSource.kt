package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueField
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/** Expected declarations come from exact original source, independently of emitted transfers. */
internal class NativePacketCompositeSuccessSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun field(path: String, owner: String, name: String): ValueField =
        ValueField.fromCompiler(endpoint(path, owner, name)).nativeRefined()

    fun endpoint(path: String, owner: String, property: String? = null): RelationEndpoint =
        ReadAction.compute<RelationEndpoint, RuntimeException> {
            val klass = PsiTreeUtil.collectElementsOfType(psi(path), KtClass::class.java).single { it.name == owner }
            val declaration: PsiNamedElement =
                if (property == null) klass else klass.primaryConstructorParameters.single { it.name == property }
            val projected = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(declaration)
            assertTrue(
                "Independent source declaration failed: $projected",
                projected is IntellijRelationDeclarationProjection.Projected,
            )
            val scope =
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_ONLY,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                )
            RelationEndpoint.resolve(
                    fixture.lease,
                    scope,
                    (projected as IntellijRelationDeclarationProjection.Projected).evidence,
                )
                .nativeRefined()
        }

    fun implicitIt(): CompositeSuccessImplicit =
        ReadAction.compute<CompositeSuccessImplicit, RuntimeException> {
            val lambda =
                PsiTreeUtil.collectElementsOfType(psi(CLIENT), KtLambdaExpression::class.java)
                    .single {
                        it.bodyExpression?.statements?.singleOrNull()?.text == "it.body.toClientResponse()"
                    }
                    .functionLiteral
            val reference =
                PsiTreeUtil.collectElementsOfType(lambda, KtNameReferenceExpression::class.java).single {
                    it.getReferencedName() == "it"
                }
            CompositeSuccessImplicit(range(lambda), range(reference))
        }

    private fun psi(path: String): KtFile {
        val relative = "src/" + path.removePrefix("src/main/kotlin/")
        val file = checkNotNull(base.findFileByRelativePath(relative))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    companion object {
        const val CLIENT = "src/main/kotlin/fixture/client/OffersClient.kt"
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        const val WIRE = "src/main/kotlin/fixture/wire/OffersApi.kt"
        private const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        val PATHS = listOf(OUTCOME, WIRE, CLIENT, FEATURE)
    }
}

internal data class CompositeSuccessImplicit(
    val body: ExactDeclarationTextRange,
    val reference: ExactDeclarationTextRange,
)
