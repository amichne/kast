package io.github.amichne.kast.relation.intellij.nativefixture

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.IndexingTestUtil
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider
import org.jetbrains.kotlin.idea.base.test.NewLightKotlinCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** In-process Platform/index authority; no parser environment or existing IDE is involved. */
class IndexedKotlinReferenceTest : NewLightKotlinCodeInsightFixtureTestCase() {
    override fun getTestDataPath(): String = "."

    fun testCrossFileReferenceAndCommittedEdit() {
        assertEquals("true", System.getProperty("java.awt.headless"))
        assertEquals("K2", System.getProperty("kotlin.plugin.mode"))
        assertTrue(KotlinPluginModeProvider.isK2Mode())
        assertNotNull(PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.kotlin")))
        val target =
            myFixture.addFileToProject("Target.kt", "package proof\nfun target() = 1\nfun unused() = 2\n") as KtFile
        val declaration = target.declarations.first() as KtNamedFunction
        val caller = myFixture.configureByText("Caller.kt", "package proof\nfun caller() = tar<caret>get()\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertTrue(caller.virtualFile is VirtualFileWithId)
        assertTrue(target.virtualFile is VirtualFileWithId)
        val indexed =
            KotlinFunctionShortNameIndex.Helper.get("target", project, GlobalSearchScope.projectScope(project))
        assertTrue(indexed.contains(declaration))
        assertSame(declaration, checkNotNull(myFixture.getReferenceAtCaretPosition()).resolve())
        val scope = GlobalSearchScope.projectScope(project)
        val references = ReferencesSearch.search(declaration, scope).findAll()
        assertEquals(1, references.size)
        assertSame(caller, references.single().element.containingFile)
        assertTrue(ReferencesSearch.search(target.declarations[1], scope).findAll().isEmpty())
        val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(caller))
        WriteCommandAction.runWriteCommandAction(project) { document.setText("package proof\nfun caller() = 2\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertTrue(ReferencesSearch.search(declaration, scope).findAll().isEmpty())
    }
}
