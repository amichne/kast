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
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Expected declarations and ranges come from the separately frozen supplemental source. */
internal class NativePacketConditionalPayloadSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun input(method: String): PacketExpression = read {
        val host = control(method)
        val call = calls(host).single { it.calleeExpression?.text == "convert" }
        val receiver = (call.parent as KtQualifiedExpression).receiverExpression
        assertEquals("source", receiver.text)
        PacketExpression(
            fixture.callableDeclaration(PacketDeclaration(PATH, "ConditionalPayloadControls", method)),
            range(receiver),
        )
    }

    fun callback(method: String): ExactDeclarationTextRange = read {
        val literal =
            PsiTreeUtil.collectElementsOfType(control(method), KtLambdaExpression::class.java).single().functionLiteral
        assertTrue("The control must preserve a genuine implicit formal", literal.valueParameters.isEmpty())
        val expected = if (method == IDENTITY) "it" else "\"constant\""
        assertEquals(expected, literal.bodyExpression?.statements?.single()?.text)
        range(literal)
    }

    fun identityRead(): ExactDeclarationTextRange = read {
        val reference =
            PsiTreeUtil.collectElementsOfType(control(IDENTITY), KtNameReferenceExpression::class.java).single {
                it.getReferencedName() == "it"
            }
        range(reference)
    }

    fun invoke(): ExactDeclarationTextRange = read { range(operationCall()) }

    fun acceptedRead(): ExactDeclarationTextRange = read {
        range(checkNotNull(operationCall().valueArguments.single().getArgumentExpression()))
    }

    fun rejectedRead(): ExactDeclarationTextRange = read {
        val call = calls(convert()).single { it.calleeExpression?.text == "rejected" }
        range(checkNotNull(call.valueArguments.single().getArgumentExpression()))
    }

    fun acceptedField(): ValueField = ValueField.fromCompiler(endpoint("Accepted", "payload")).nativeRefined()

    fun rejectedField(): ValueField = ValueField.fromCompiler(endpoint("Rejected", "payload")).nativeRefined()

    fun acceptedType(): RelationEndpoint = endpoint("Accepted")

    fun convertSelector() = fixture.callableDeclaration(PacketDeclaration(PATH, "Parcel", "convert"))

    private fun endpoint(owner: String, property: String? = null): RelationEndpoint = read {
        val klass = PsiTreeUtil.collectElementsOfType(psi(), KtClass::class.java).single { it.name == owner }
        val declaration: PsiNamedElement =
            if (property == null) klass else klass.primaryConstructorParameters.single { it.name == property }
        val result = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(declaration)
        assertTrue(
            "Independent control declaration failed: $result",
            result is IntellijRelationDeclarationProjection.Projected,
        )
        RelationEndpoint.resolve(
                fixture.lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_ONLY,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
                (result as IntellijRelationDeclarationProjection.Projected).evidence,
            )
            .nativeRefined()
    }

    private fun operationCall(): KtCallExpression = calls(convert()).single { it.calleeExpression?.text == "operation" }

    private fun convert(): KtNamedFunction =
        PsiTreeUtil.collectElementsOfType(psi(), KtNamedFunction::class.java).single { it.name == "convert" }

    private fun control(method: String): KtNamedFunction {
        val owner =
            PsiTreeUtil.collectElementsOfType(psi(), KtObjectDeclaration::class.java).single {
                it.name == "ConditionalPayloadControls"
            }
        return owner.declarations.filterIsInstance<KtNamedFunction>().single { it.name == method }
    }

    private fun calls(owner: KtNamedFunction) = PsiTreeUtil.collectElementsOfType(owner, KtCallExpression::class.java)

    private fun psi(): KtFile {
        val relative = "src/" + PATH.removePrefix("src/main/kotlin/")
        val file = checkNotNull(base.findFileByRelativePath(relative))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException>(action)

    companion object {
        const val PATH = "src/main/kotlin/fixture/controls/ConditionalPayloadControls.kt"
        const val IDENTITY = "identity"
        const val CONSTANT = "constant"
    }
}
