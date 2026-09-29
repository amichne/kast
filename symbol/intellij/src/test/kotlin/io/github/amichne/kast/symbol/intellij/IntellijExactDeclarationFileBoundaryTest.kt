package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.lang.reflect.Proxy
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs the production lookup; only external PSI observations are scripted. */
class IntellijExactDeclarationFileBoundaryTest {
    private val key =
        IntellijExactDeclarationLookupKey(
            file =
                SymbolDiscoveryFileIdentity.fromBoundary(
                        workspaceRoot = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                        nativePath = Path.of("/workspace/src/Service.kt"),
                        virtualFileUrl = "file:///workspace/src/Service.kt",
                    )
                    .refined(),
            offset = SymbolDiscoverySourceOffset.parse(7).refined(),
            name = SymbolDiscoveryCandidateName.parse("service").refined(),
        )

    @TestFactory
    fun `directory collisions do not change declaration evidence`(): List<DynamicTest> =
        (0..4).map { depth ->
            DynamicTest.dynamicTest("matching directory $depth ancestors above the file") {
                var directory: PsiElement = psi<PsiDirectory>(name = "service")
                repeat(depth) { directory = psi<PsiDirectory>(name = "unrelated$it", parent = directory) }
                val file = psi<PsiFile>(name = "Service.kt", range = TextRange(0, 100), parent = directory)
                val declaration = psi<PsiNamedElement>("service", TextRange(7, 35), file)
                assertFound(declaration, psi<PsiElement>(parent = declaration))
            }
        }

    @Test
    fun `file boundary is not inspected and its parent is never requested`() {
        val file = inaccessible<PsiFile>()
        val declaration = psi<PsiNamedElement>("service", TextRange(7, 35), file)
        assertFound(declaration)
    }

    @Test
    fun `offset mismatch continuation also respects the file boundary`() {
        val outer = psi<PsiNamedElement>("service", TextRange(0, 70), inaccessible<PsiFile>())
        val declaration = psi<PsiNamedElement>("service", TextRange(7, 35), outer)
        assertFound(declaration)
    }

    @Test
    fun `a matching filename at offset zero is not a declaration or an ambiguity`() {
        val zeroKey = key.copy(offset = SymbolDiscoverySourceOffset.parse(0).refined())
        val file = psi<PsiFile>("service", TextRange(0, 35))
        val declaration = psi<PsiNamedElement>("service", TextRange(0, 35), file)
        val found =
            assertInstanceOf(
                IntellijLiveExactDeclarationLookupResult.Found::class.java,
                findExactDeclarationAncestor(declaration, zeroKey),
            )
        assertSame(declaration, found.declaration)
        assertEquals(0, found.evidence.range.startInclusive)
        assertEquals(35, found.evidence.range.endExclusive)
    }

    @Test
    fun `a file cannot supply a missing declaration`() {
        val zeroKey = key.copy(offset = SymbolDiscoverySourceOffset.parse(0).refined())
        val file = psi<PsiFile>("service", TextRange(0, 35))
        assertEquals(
            rejected(IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION),
            findExactDeclarationAncestor(psi<PsiElement>(parent = file), zeroKey),
        )
    }

    @Test
    fun `filesystem items cannot be lookup starting declarations`() {
        for (item in listOf(inaccessible<PsiFile>(), inaccessible<PsiDirectory>())) {
            assertEquals(
                rejected(IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION),
                findExactDeclarationAncestor(item, key),
            )
        }
    }

    @Test
    fun `two in-file exact matches still reject as ambiguous`() {
        val outer = psi<PsiNamedElement>("service", TextRange(7, 70), inaccessible<PsiFile>())
        val inner = psi<PsiNamedElement>("service", TextRange(7, 35), outer)
        assertEquals(
            rejected(IntellijExactDeclarationLookupRejection.AMBIGUOUS_DECLARATION),
            findExactDeclarationAncestor(inner, key),
        )
    }

    @Test
    fun `a found match does not hide an invalid in-file ancestor`() {
        val invalid = psi<PsiElement>(parent = inaccessible<PsiFile>(), valid = false)
        val declaration = psi<PsiNamedElement>("service", TextRange(7, 35), invalid)
        assertEquals(
            rejected(IntellijExactDeclarationLookupRejection.STALE_LOCATION),
            findExactDeclarationAncestor(declaration, key),
        )
    }

    @Test
    fun `a found match does not hide a rangeless matching in-file ancestor`() {
        val rangeless = psi<PsiNamedElement>("service", parent = inaccessible<PsiFile>())
        val declaration = psi<PsiNamedElement>("service", TextRange(7, 35), rangeless)
        assertEquals(
            rejected(IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION),
            findExactDeclarationAncestor(declaration, key),
        )
    }

    @Test
    fun `wrong names and offsets do not fall back to a nearby declaration`() {
        for (declaration in
            listOf(
                psi<PsiNamedElement>("other", TextRange(7, 35), inaccessible<PsiFile>()),
                psi<PsiNamedElement>("service", TextRange(8, 35), inaccessible<PsiFile>()),
            )) {
            assertEquals(
                rejected(IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION),
                findExactDeclarationAncestor(declaration, key),
            )
        }
    }

    private fun assertFound(declaration: PsiNamedElement, leaf: PsiElement = declaration) {
        val found =
            assertInstanceOf(
                IntellijLiveExactDeclarationLookupResult.Found::class.java,
                findExactDeclarationAncestor(leaf, key),
            )
        assertSame(declaration, found.declaration)
        assertEquals(key.file, found.evidence.file)
        assertEquals(key.name, found.evidence.name)
        assertEquals(7, found.evidence.range.startInclusive)
        assertEquals(35, found.evidence.range.endExclusive)
        assertEquals(declaration.javaClass.name, found.evidence.runtimeType.value)
    }

    private fun rejected(reason: IntellijExactDeclarationLookupRejection) =
        IntellijLiveExactDeclarationLookupResult.Rejected(reason)

    private inline fun <reified T : PsiElement> inaccessible(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("lookup crossed the source boundary: ${method.name}")
        } as T

    private inline fun <reified T : PsiElement> psi(
        name: String? = null,
        range: TextRange? = null,
        parent: PsiElement? = null,
        valid: Boolean = true,
    ): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
            when (method.name) {
                "isValid" -> valid
                "getName" -> name
                "getTextRange" -> range
                "getParent" -> parent
                "equals" -> instance === args?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "${T::class.java.simpleName}($name)"
                else -> error("unexpected PSI access: ${method.name}")
            }
        } as T

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
