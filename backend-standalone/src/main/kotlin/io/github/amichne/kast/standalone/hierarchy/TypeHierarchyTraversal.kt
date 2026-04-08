package io.github.amichne.kast.standalone.hierarchy

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.TypeHierarchyDirection
import io.github.amichne.kast.api.TypeHierarchyNode
import io.github.amichne.kast.api.TypeHierarchyQuery
import io.github.amichne.kast.api.TypeHierarchyResult
import io.github.amichne.kast.api.TypeHierarchyStats
import io.github.amichne.kast.api.TypeHierarchyTruncation
import io.github.amichne.kast.api.TypeHierarchyTruncationReason
import io.github.amichne.kast.standalone.StandaloneAnalysisSession
import io.github.amichne.kast.standalone.analysis.resolveTarget
import io.github.amichne.kast.standalone.analysis.resolvedFilePath
import io.github.amichne.kast.standalone.analysis.supertypeNames
import io.github.amichne.kast.standalone.analysis.toSymbolModel
import io.github.amichne.kast.standalone.analysis.typeHierarchyDeclaration
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

internal class TypeHierarchyTraversal(
    private val session: StandaloneAnalysisSession,
) {
    fun build(query: TypeHierarchyQuery): TypeHierarchyResult {
        val file = session.findKtFile(query.position.filePath)
        val resolvedTarget = resolveTarget(file, query.position.offset)
        val rootTarget = resolvedTarget.typeHierarchyDeclaration() ?: resolvedTarget
        val budget = TypeHierarchyBudget(maxResults = query.maxResults.coerceAtLeast(1))
        val root = buildNode(
            target = rootTarget,
            direction = query.direction,
            depthRemaining = query.depth.coerceAtLeast(0),
            pathKeys = emptySet(),
            budget = budget,
            currentDepth = 0,
        )
        return TypeHierarchyResult(
            root = root,
            stats = budget.toStats(),
        )
    }

    private fun buildNode(
        target: PsiElement,
        direction: TypeHierarchyDirection,
        depthRemaining: Int,
        pathKeys: Set<String>,
        budget: TypeHierarchyBudget,
        currentDepth: Int,
    ): TypeHierarchyNode {
        val symbol = symbolFor(target)
        val nodeKey = target.typeHierarchySymbolIdentityKey(symbol)
        budget.recordNode(depth = currentDepth)

        if (depthRemaining == 0 || !target.isTypeHierarchyExpandable()) {
            return TypeHierarchyNode(
                symbol = symbol,
                children = emptyList(),
            )
        }

        val children = mutableListOf<TypeHierarchyNode>()
        var truncation: TypeHierarchyTruncation? = null

        for (edge in findEdges(target, direction)) {
            if (budget.totalNodes >= budget.maxResults) {
                truncation = TypeHierarchyTruncation(
                    reason = TypeHierarchyTruncationReason.MAX_RESULTS,
                    details = "Reached maxResults=${budget.maxResults}",
                )
                break
            }

            val childKey = edge.target.typeHierarchySymbolIdentityKey(edge.symbol)
            val child = if (childKey == nodeKey || childKey in pathKeys) {
                budget.recordNode(depth = currentDepth + 1)
                budget.recordTruncation()
                TypeHierarchyNode(
                    symbol = edge.symbol,
                    truncation = TypeHierarchyTruncation(
                        reason = TypeHierarchyTruncationReason.CYCLE,
                        details = "Cycle detected for symbol=$childKey",
                    ),
                    children = emptyList(),
                )
            } else {
                buildNode(
                    target = edge.target,
                    direction = direction,
                    depthRemaining = depthRemaining - 1,
                    pathKeys = pathKeys + nodeKey,
                    budget = budget,
                    currentDepth = currentDepth + 1,
                )
            }
            children += child
        }

        if (truncation != null) {
            budget.recordTruncation()
        }

        return TypeHierarchyNode(
            symbol = symbol,
            truncation = truncation,
            children = children,
        )
    }

    private fun findEdges(
        target: PsiElement,
        direction: TypeHierarchyDirection,
    ): List<TypeHierarchyEdge> {
        val edges = when (direction) {
            TypeHierarchyDirection.SUPERTYPES -> supertypeEdges(target)
            TypeHierarchyDirection.SUBTYPES -> subtypeEdges(target)
            TypeHierarchyDirection.BOTH -> supertypeEdges(target) + subtypeEdges(target)
        }
        return edges
            .distinctBy { edge -> edge.target.typeHierarchySymbolIdentityKey(edge.symbol) }
            .sortedWith(
                compareBy(
                    { it.symbol.fqName },
                    { it.symbol.kind.name },
                    { it.symbol.location.filePath },
                    { it.symbol.location.startOffset },
                ),
            )
    }

    private fun supertypeEdges(target: PsiElement): List<TypeHierarchyEdge> = directSupertypeNames(target)
        .mapNotNull(::findWorkspaceTypeByFqName)
        .map { declaration ->
            TypeHierarchyEdge(
                target = declaration,
                symbol = symbolFor(declaration),
            )
        }

    private fun subtypeEdges(target: PsiElement): List<TypeHierarchyEdge> {
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

    private fun symbolFor(target: PsiElement): Symbol {
        val supertypes = directSupertypeNames(target).takeUnless { it.isEmpty() }
        return when (target) {
            is KtClassOrObject -> analyze(target.containingKtFile) {
                target.toSymbolModel(
                    containingDeclaration = null,
                    supertypes = supertypes,
                )
            }

            else -> target.toSymbolModel(
                containingDeclaration = null,
                supertypes = supertypes,
            )
        }
    }

    private fun directSupertypeNames(target: PsiElement): List<String> = when (target) {
        is KtClassOrObject -> analyze(target.containingKtFile) { supertypeNames(target).orEmpty() }
        is PsiClass -> target.supers.mapNotNull(PsiClass::getQualifiedName).distinct().sorted()
        else -> emptyList()
    }

    private fun findWorkspaceTypeByFqName(fqName: String): PsiElement? = session.allKtFiles()
        .asSequence()
        .flatMap { file -> file.namedTypeDeclarations().asSequence() }
        .firstOrNull { declaration -> declaration.fqName?.asString() == fqName }
}

private data class TypeHierarchyEdge(
    val target: PsiElement,
    val symbol: Symbol,
)

private class TypeHierarchyBudget(
    val maxResults: Int,
) {
    var totalNodes: Int = 0
    var maxDepthReached: Int = 0
    var truncated: Boolean = false

    fun recordNode(depth: Int) {
        totalNodes += 1
        if (depth > maxDepthReached) {
            maxDepthReached = depth
        }
    }

    fun recordTruncation() {
        truncated = true
    }

    fun toStats(): TypeHierarchyStats = TypeHierarchyStats(
        totalNodes = totalNodes,
        maxDepthReached = maxDepthReached,
        truncated = truncated,
    )
}

private fun PsiElement.isTypeHierarchyExpandable(): Boolean = this is KtClassOrObject || this is PsiClass

private fun PsiElement.typeHierarchySymbolIdentityKey(symbol: Symbol): String = buildString {
    append(symbol.fqName)
    append('|')
    append(resolvedFilePath().value)
    append(':')
    append(symbol.location.startOffset)
    append('-')
    append(symbol.location.endOffset)
}

private fun KtFile.namedTypeDeclarations(): List<KtClassOrObject> {
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
