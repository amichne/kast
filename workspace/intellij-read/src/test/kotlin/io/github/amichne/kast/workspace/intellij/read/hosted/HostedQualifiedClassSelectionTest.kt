package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.intellij.read.FIXTURE_ROOT
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedQualifiedClassSelectionTest {
    @Test fun `qualified selection retains exact root and structured class identity`() {
        val selected = (HostedQualifiedClassSelection.parse(FIXTURE_ROOT, "example.Outer.Child") as Refinement.Refined).value
        assertEquals(FIXTURE_ROOT, selected.root)
        assertEquals("example.Outer.Child", selected.signature.qualifiedIdentity.value)
        assertTrue(HostedQualifiedClassSelection.parse(FIXTURE_ROOT, "DefaultPackageClass") is Refinement.Refined)
        for (invalid in listOf("", ".Child", "example..Child", "example.Child.", "example/Child", "example.*", "a".repeat(4097), "example.Child\n")) {
            assertEquals(Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION), HostedQualifiedClassSelection.parse(FIXTURE_ROOT, invalid))
        }
    }

    @Test fun `only a unique complete declaration collection supplies the stronger candidate`() {
        assertEquals(Refinement.Rejected(HostedQueryFailure.DECLARATION_NOT_FOUND), HostedUniqueDeclaration.select(emptyList<Any>()))
        val declaration = Any()
        val unique = (HostedUniqueDeclaration.select(listOf(declaration)) as Refinement.Refined).value
        assertSame(declaration, unique.value)
        assertEquals(Refinement.Rejected(HostedQueryFailure.AMBIGUOUS_DECLARATION), HostedUniqueDeclaration.select(listOf(Any(), Any())))
    }

    @Test fun `index selection requires the exact compiler class signature`() {
        val selected = (HostedQualifiedClassSelection.parse(FIXTURE_ROOT, "example.Child") as Refinement.Refined).value
        assertEquals(Refinement.Refined(Unit), selected.verify(selected.signature))
        val other = (CanonicalCompilerSignature.classLike("other.Child") as Refinement.Refined).value
        val alias = (CanonicalCompilerSignature.typeAlias("example.Child") as Refinement.Refined).value
        for (mismatch in listOf(other, alias)) {
            assertEquals(Refinement.Rejected(HostedQueryFailure.DECLARATION_IDENTITY_MISMATCH), selected.verify(mismatch))
        }
    }
}
