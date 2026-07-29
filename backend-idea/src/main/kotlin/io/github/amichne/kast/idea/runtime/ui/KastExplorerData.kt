package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
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
    val incoming = referencesToSymbol(declaration.fqName)
        .asSequence()
        .take(MAX_RELATIONS_PER_LAYER)
        .map(::incomingRelation)
    val outgoing = referencesFromFile(declaration.filePath)
        .asSequence()
        .filter { reference -> reference.sourceFqName == declaration.fqName }
        .take(MAX_RELATIONS_PER_LAYER)
        .map(::outgoingRelation)
    val graph = semanticGraphRelations(workspaceRoot, item)
    return KastExplorerResult.Inspection(
        KastExplorerInspection(
            selected = item,
            relations = (incoming + outgoing + graph)
                .distinctBy { relation ->
                    Triple(relation.layer, relation.title, relation.navigationTarget)
                }
                .toList(),
        ),
    )
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

private fun outgoingRelation(reference: SymbolReferenceRow): KastExplorerRelation =
    KastExplorerRelation(
        layer = KastExplorerEvidenceLayer.OUTGOING,
        title = NonBlankString(reference.targetFqName),
        detail = NonBlankString(reference.edgeKind.displayName()),
        navigationTarget = reference.targetPath?.let { path ->
            reference.targetOffset?.let { offset -> sourceTarget(path, offset) }
        },
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
    val allSymbols = (snapshot.symbols + snapshot.boundarySymbols)
        .associateBy { symbol -> symbol.canonicalKey }
    val selectedKeys = snapshot.symbols
        .asSequence()
        .filter { symbol -> symbol.fqName?.value == item.declaration.fqName }
        .map { symbol -> symbol.canonicalKey }
        .toSet()
    return snapshot.relations
        .asSequence()
        .filter { relation -> relation.sourceKey in selectedKeys }
        .take(MAX_RELATIONS_PER_LAYER)
        .mapNotNull { relation ->
            val target = allSymbols[relation.resolvedTargetKey ?: relation.targetKey] ?: return@mapNotNull null
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
