package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.nio.file.Path

internal fun SqliteSourceIndexStore.explore(
    workspaceRoot: Path,
    request: KastExplorerRequest,
): KastExplorerResult = when (request) {
    KastExplorerRequest.Overview -> semanticGraphScopeSnapshot().let { snapshot ->
        KastExplorerResult.Overview(
            KastExplorerOverview(
                graphGeneration = snapshot.generation,
                graphFileCount = NonNegativeInt(snapshot.sourcePaths.size),
            ),
        )
    }
    is KastExplorerRequest.Search -> KastExplorerResult.SearchResults(
        searchDeclarations(request.pattern, request.maxResults).map(::KastExplorerSearchItem),
    )
    is KastExplorerRequest.Inspect -> inspect(workspaceRoot, request.item)
}

private fun SqliteSourceIndexStore.inspect(
    workspaceRoot: Path,
    item: KastExplorerSearchItem,
): KastExplorerResult.Inspection {
    val declaration = item.declaration
    val incoming = exactIncomingRelations(declaration)
    val graph = semanticGraphRelations(workspaceRoot, item)
    return KastExplorerResult.Inspection(
        KastExplorerInspection(
            selected = item,
            relations = (incoming + graph)
                .distinctBy { relation ->
                    Triple(relation.layer, relation.title, relation.navigationTarget)
                }
                .toList(),
        ),
    )
}

private fun SqliteSourceIndexStore.exactIncomingRelations(
    declaration: DeclarationRow,
): Sequence<KastExplorerRelation> {
    val declarationOffset = declaration.declarationOffset ?: return emptySequence()
    val page = generatedReferencePageToExactSymbol(
        target = ExactReferenceTarget(
            fqName = declaration.fqName,
            declarationFile = NormalizedPath.parse(declaration.filePath),
            declarationStartOffset = NonNegativeInt(declarationOffset),
        ),
        offset = NonNegativeInt(0),
        maxResults = PositiveInt(MAX_RELATIONS_PER_LAYER),
    )
    return if (page.exactIdentityAvailable) {
        page.page.references.asSequence().map(::incomingRelation)
    } else {
        emptySequence()
    }
}

private fun incomingRelation(reference: SymbolReferenceRow): KastExplorerRelation =
    KastExplorerRelation(
        layer = KastExplorerEvidenceLayer.INCOMING,
        title = NonBlankString(
            reference.sourceFqName?.takeIf(String::isNotBlank)
                ?: Path.of(reference.sourcePath).fileName.toString(),
        ),
        detail = NonBlankString(reference.edgeKind.displayName()),
        navigationTarget = sourceTarget(reference.sourcePath, reference.sourceOffset),
    )

private fun SqliteSourceIndexStore.semanticGraphRelations(
    workspaceRoot: Path,
    item: KastExplorerSearchItem,
): Sequence<KastExplorerRelation> {
    val declarationPath = Path.of(item.declaration.filePath).toAbsolutePath().normalize()
    if (!declarationPath.startsWith(workspaceRoot)) return emptySequence()
    val relativePath = SemanticGraphSourcePath.parse(
        workspaceRoot.relativize(declarationPath).toString(),
    )
    val snapshot = readSemanticGraph(listOf(relativePath))
    val declarationOffset = item.declaration.declarationOffset ?: return emptySequence()
    val allSymbols = (snapshot.symbols + snapshot.boundarySymbols)
        .associateBy { symbol -> symbol.canonicalKey }
    val selectedKeys = snapshot.symbols
        .asSequence()
        .filter { symbol ->
            symbol.fqName?.value == item.declaration.fqName &&
                declarationOffset in symbol.startOffset.value until symbol.endOffset.value
        }
        .map { symbol -> symbol.canonicalKey }
        .toSet()
    return snapshot.relations
        .asSequence()
        .filter { relation -> relation.sourceKey in selectedKeys }
        .take(MAX_RELATIONS_PER_LAYER)
        .mapNotNull { relation ->
            val target = relation.resolvedTargetKey?.let(allSymbols::get)
                ?: allSymbols[relation.targetKey]
                ?: return@mapNotNull null
            KastExplorerRelation(
                layer = KastExplorerEvidenceLayer.SEMANTIC_GRAPH,
                title = NonBlankString(target.fqName?.value ?: target.name.value),
                detail = NonBlankString(
                    listOf(relation.kind.name, relation.context.name)
                        .filterNot { value -> value == "NONE" }
                        .joinToString(" · ") { value -> value.replace('_', ' ').lowercase() },
                ),
                navigationTarget = sourceTarget(
                    workspaceRoot.resolve(target.path.value).toString(),
                    target.startOffset.value,
                ),
            )
        }
}

private fun sourceTarget(
    path: String,
    offset: Int,
): KastSourceTarget = KastSourceTarget(
    Path.of(path).toAbsolutePath().normalize(),
    offset,
)

private fun Enum<*>.displayName(): String = name.replace('_', ' ').lowercase()

// ponytail: keep one screenful per evidence layer; add continuation-backed paging if users hit this ceiling.
private const val MAX_RELATIONS_PER_LAYER = 250
