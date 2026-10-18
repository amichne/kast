package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.Processor
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinExactPackagesIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTypeAliasShortNameIndex
import org.jetbrains.kotlin.psi.KtFile

/** Native supply only. The dispatcher owns index selection, admission, capacity and projection order. */
internal interface IntellijDeclarationIndexes {
    fun candidateFiles(
        name: IntellijIndexedDeclarationName,
        scope: GlobalSearchScope,
        accept: (VirtualFile) -> Boolean,
    ): Boolean

    fun named(
        index: IntellijNamedDeclarationIndex,
        name: String,
        scope: GlobalSearchScope,
        accept: (NavigationItem) -> Boolean,
    ): Boolean

    fun kotlinFiles(scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean

    fun packageFiles(packageName: String, scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean
}

internal enum class IntellijNamedDeclarationIndex(val kind: CompilerSymbolKind) {
    CLASS(CompilerSymbolKind.CLASSLIKE),
    FUNCTION(CompilerSymbolKind.FUNCTION),
    PROPERTY(CompilerSymbolKind.PROPERTY),
    TYPE_ALIAS(CompilerSymbolKind.TYPE_ALIAS),
}

internal class NativeIntellijDeclarationIndexes(private val project: Project) : IntellijDeclarationIndexes {
    override fun candidateFiles(
        name: IntellijIndexedDeclarationName,
        scope: GlobalSearchScope,
        accept: (VirtualFile) -> Boolean,
    ): Boolean =
        PsiSearchHelper.getInstance(project)
            .processCandidateFilesForText(scope, UsageSearchContext.IN_CODE, true, name.word.value, Processor(accept))

    override fun named(
        index: IntellijNamedDeclarationIndex,
        name: String,
        scope: GlobalSearchScope,
        accept: (NavigationItem) -> Boolean,
    ): Boolean =
        when (index) {
            IntellijNamedDeclarationIndex.CLASS ->
                KotlinClassShortNameIndex.processElements(name, project, scope) { accept(it) }
            IntellijNamedDeclarationIndex.FUNCTION ->
                KotlinFunctionShortNameIndex.processElements(name, project, scope) { accept(it) }
            IntellijNamedDeclarationIndex.PROPERTY ->
                KotlinPropertyShortNameIndex.processElements(name, project, scope) { accept(it) }
            IntellijNamedDeclarationIndex.TYPE_ALIAS ->
                KotlinTypeAliasShortNameIndex.processElements(name, project, scope) { accept(it) }
        }

    override fun kotlinFiles(scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean =
        FileTypeIndex.processFiles(KotlinFileType.INSTANCE, { accept(it) }, scope)

    override fun packageFiles(
        packageName: String,
        scope: GlobalSearchScope,
        accept: (VirtualFile) -> Boolean,
    ): Boolean =
        StubIndex.getInstance().processElements(
            KotlinExactPackagesIndex.NAME,
            packageName,
            project,
            scope,
            KtFile::class.java,
        ) {
            accept(it.virtualFile)
        }
}
