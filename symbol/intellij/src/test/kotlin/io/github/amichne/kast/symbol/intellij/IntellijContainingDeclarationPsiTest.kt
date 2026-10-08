package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real file-backed Kotlin PSI; K2 refinement and installed-host timing remain separate proofs. */
class IntellijContainingDeclarationPsiTest {
    @Test
    fun `generic and value parameter positions resolve their containing interface method`(@TempDir home: Path) =
        withParser(home) { project ->
            // The supplementary character makes UTF-16 offsets differ from Unicode code-point offsets.
            val text =
                """
                package example.cache
                // 😀
                interface CacheManager {
                    fun <T : Any> execute(load: () -> T, useCached: (T) -> Boolean): T
                }
                class RedisCacheManager : CacheManager {
                    override fun <T : Any> execute(load: () -> T, useCached: (T) -> Boolean): T = load()
                }
                """
                    .trimIndent()
            val file = fixture(project, home.resolve("cache"), mapOf("CacheManager.kt" to text)).files.single()
            ApplicationManager.getApplication().runReadAction {
                assertTrue(file.isPhysical)
                assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
                val declarationStart = text.indexOf("fun <T : Any>")
                val method = assertInstanceOf(KtNamedFunction::class.java, file.declarationAt(text.indexOf("execute(")))
                assertEquals("execute", method.name)
                assertEquals(declarationStart, method.textRange.startOffset)
                for (offset in listOf(text.indexOf("<T") + 1, text.indexOf("Any"), text.indexOf("load:"))) {
                    assertTrue(method.textRange.contains(offset))
                    assertSame(method, file.declarationAt(offset), "UTF-16 offset $offset")
                }
                val implementation =
                    assertInstanceOf(KtNamedFunction::class.java, file.declarationAt(text.lastIndexOf("load()")))
                assertEquals("execute", implementation.name)
                assertEquals(text.indexOf("override fun"), implementation.textRange.startOffset)
                assertNull(file.declarationAt(text.indexOf("example.cache")))
                assertNull(file.declarationAt(text.length))
            }
        }

    @Test
    fun `a supported local property retains nearest containing declaration ownership`(@TempDir home: Path) =
        withParser(home) { project ->
            val text = "fun enclosing() { val local = 42 }"
            val file = fixture(project, home.resolve("local"), mapOf("Local.kt" to text)).files.single()
            ApplicationManager.getApplication().runReadAction {
                val property = assertInstanceOf(KtProperty::class.java, file.declarationAt(text.indexOf("42")))
                assertEquals("local", property.name)
                assertEquals(text.indexOf("val local"), property.textRange.startOffset)
            }
        }
}
