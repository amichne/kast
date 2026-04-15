package io.github.amichne.kast.standalone.hierarchy

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.shared.analysis.supertypeNames
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.hierarchy.TypeEdgeResolver
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyEdge
import io.github.amichne.kast.standalone.StandaloneAnalysisSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/** Standalone implementation of [TypeEdgeResolver] using K2 PSI file scanning. */
internal class StandaloneTypeEdgeResolver(
    private val session: StandaloneAnalysisSession,
) : TypeEdgeResolver {

    override fun supertypeEdges(target: PsiElement): List<TypeHierarchyEdge> =
        directSupertypeNames(target)
            .mapNotNull(::findWorkspaceTypeByFqName)
            .map { declaration ->
                TypeHierarchyEdge(
                    target = declaration,
                    symbol = symbolFor(declaration),
                )
            }

    override fun subtypeEdges(target: PsiElement): List<TypeHierarchyEdge> {
        val targetFqName = symbolFor(target).fqName
        return session.allKtFiles()
            .flatMap(KtFile::namedTypeDeclarations)
            .filterNot { candidate -> candidate === target }
            .filter { candidate -> targetFqName in directSupertypeNames(candidate) }
            .map { candidate ->
                TypeHierarchyEdge(
                    target = candidate,
                    symbol = symbolFor(candidate),
                )
            }
    }

    override fun symbolFor(target: PsiElement): io.github.amichne.kast.api.Symbol {
        val supertypes = directSupertypeNames(target).takeUnless { it.isEmpty() }
        return when (target) {
            is KtClassOrObject -> analyze(target.containingKtFile) {
                target.toSymbolModel(containingDeclaration = null, supertypes = supertypes)
            }
            else -> target.toSymbolModel(containingDeclaration = null, supertypes = supertypes)
        }
    }

    fun directSupertypeNames(target: PsiElement): List<String> = when (target) {
        is KtClassOrObject -> analyze(target.containingKtFile) { supertypeNames(target).orEmpty() }
        is PsiClass -> target.supers.mapNotNull(PsiClass::getQualifiedName).distinct().sorted()
        else -> emptyList()
    }

    private fun findWorkspaceTypeByFqName(fqName: String): PsiElement? = session.allKtFiles()
        .asSequence()
        .flatMap { file -> file.namedTypeDeclarations().asSequence() }
        .firstOrNull { declaration -> declaration.fqName?.asString() == fqName }
}

internal fun KtFile.namedTypeDeclarations(): List<KtClassOrObject> {
    val declarations = mutableListOf<KtClassOrObject>()
    accept(
        object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is KtClassOrObject && !element.name.isNullOrBlank()) {
                    declarations += element
                }
                super.visitElement(element)
            }
        },
    )
    return declarations
}
