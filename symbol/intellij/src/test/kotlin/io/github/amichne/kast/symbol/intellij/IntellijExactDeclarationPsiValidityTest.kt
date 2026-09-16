package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntellijExactDeclarationPsiValidityTest {
    @Test
    fun `invalid and rangeless matching PSI reject without omitting the declaration`() {
        val key =
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
        fun declaration(valid: Boolean, range: com.intellij.openapi.util.TextRange?) =
            java.lang.reflect.Proxy.newProxyInstance(
                com.intellij.psi.PsiNamedElement::class.java.classLoader,
                arrayOf(com.intellij.psi.PsiNamedElement::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "isValid" -> valid
                    "getName" -> "service"
                    "getTextRange" -> range
                    "getParent" -> null
                    else -> error("unexpected PSI access: ${method.name}")
                }
            } as com.intellij.psi.PsiNamedElement
        assertEquals(
            IntellijLiveExactDeclarationLookupResult.Rejected(IntellijExactDeclarationLookupRejection.STALE_LOCATION),
            findExactDeclarationAncestor(declaration(false, null), key),
        )
        assertEquals(
            IntellijLiveExactDeclarationLookupResult.Rejected(
                IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION
            ),
            findExactDeclarationAncestor(declaration(true, null), key),
        )
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
