package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Structural PSI ownership proof only; K2 owner confirmation remains a separate qualification. */
class LocalDeclarationLexicalOwnersTest {
    @Test
    fun `local lexical anchors preserve shadowed scopes and reject an unrelated owner`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val source = "fun outer() { val same = 1; run { val same = 2 }; fun local() { val same = 3 } }"
            val file = factory.createFile(source)
            assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
            val outer = file.declarations.filterIsInstance<KtNamedFunction>().single()
            val local = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).single { it.name == "local" }
            val properties =
                PsiTreeUtil.findChildrenOfType(file, org.jetbrains.kotlin.psi.KtProperty::class.java).sortedBy {
                    it.textRange.startOffset
                }
            fun range(start: Int, end: Int) =
                when (
                    val admitted = io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange.parse(start, end)
                ) {
                    is io.github.amichne.kast.kernel.Refinement.Refined -> admitted.value
                    is io.github.amichne.kast.kernel.Refinement.Rejected ->
                        error("Invalid independently expected range")
                }
            fun anchors(declaration: com.intellij.psi.PsiElement, owner: com.intellij.psi.PsiElement) =
                when (val admitted = localDeclarationLexicalOwners(declaration, owner)) {
                    is io.github.amichne.kast.kernel.Refinement.Refined -> admitted.value
                    is io.github.amichne.kast.kernel.Refinement.Rejected ->
                        error("Unexpected structural rejection: ${admitted.failure}")
                }
            val outerBlock = range(source.indexOf('{'), source.length)
            val lambdaBlock =
                range(source.indexOf('{', source.indexOf("run")), source.indexOf('}', source.indexOf("run")) + 1)
            val lambdaBody =
                range(source.indexOf("val same = 2"), source.indexOf("val same = 2") + "val same = 2".length)
            val localBlock =
                range(
                    source.indexOf('{', source.indexOf("fun local")),
                    source.indexOf('}', source.indexOf("fun local")) + 1,
                )
            assertEquals(listOf(outerBlock), anchors(properties[0], outer))
            assertEquals(listOf(outerBlock, lambdaBlock, lambdaBody), anchors(properties[1], outer))
            assertEquals(listOf(localBlock), anchors(properties[2], local))
            val rejected =
                localDeclarationLexicalOwners(properties[0], local) as io.github.amichne.kast.kernel.Refinement.Rejected
            assertEquals(
                io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure.LexicalAncestryUnavailable,
                rejected.failure,
            )
        }
}
