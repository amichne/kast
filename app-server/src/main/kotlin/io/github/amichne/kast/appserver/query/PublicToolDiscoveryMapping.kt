package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryPackageScopeDocument
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.isIndexedQueryWord

internal fun PublicToolTextSource.lowerTextSource(): Refinement<QueryFromDocument, PublicToolInputFailure> =
    if (!word.isIndexedQueryWord()) {
        rejected(PublicToolParameter.SOURCE_WORD, PublicToolRule.INDEXED_WORD)
    } else {
        when (val admittedScope = (scope ?: PublicToolDefaults.scope).lowerDiscoveryScope()) {
            is Refinement.Rejected -> admittedScope
            is Refinement.Refined ->
                Refinement.Refined(
                    QueryFromDocument.TextWord(
                        word,
                        admittedScope.value,
                        bounded(
                            (declarationKinds ?: PublicToolDefaults.declarationKinds).values.map {
                                it.lowerDiscoveryKind()
                            }
                        ),
                    )
                )
        }
    }

internal fun PublicToolScope.lowerDiscoveryScope(): Refinement<QueryScopeDocument, PublicToolInputFailure> =
    when (this) {
        is PublicToolDirectoryScope ->
            when (val path = WorkspaceRelativePath.parse(relativeDirectoryPath.value)) {
                is Refinement.Rejected ->
                    rejected(PublicToolParameter.DIRECTORY, PublicToolRule.WORKSPACE_RELATIVE_PATH)
                is Refinement.Refined ->
                    Refinement.Refined(
                        QueryScopeDocument(
                            sourceSetNames ?: PublicToolDefaults.sourceSets,
                            QueryDirectoryScopeDocument(
                                proven(ProtocolText.parse(path.value.value)),
                                containment(includeSubdirectories ?: PublicToolDefaults.includeSubdirectories),
                            ),
                            null,
                        )
                    )
            }
        is PublicToolPackageScope ->
            when (val name = PublicQueryPackageName.parse(packageName.value)) {
                is Refinement.Rejected -> rejected(PublicToolParameter.PACKAGE, PublicToolRule.PACKAGE_NAME)
                is Refinement.Refined ->
                    Refinement.Refined(
                        QueryScopeDocument(
                            sourceSetNames ?: PublicToolDefaults.sourceSets,
                            null,
                            QueryPackageScopeDocument(
                                proven(ProtocolText.parse(name.value.value)),
                                containment(includeSubpackages ?: PublicToolDefaults.includeSubpackages),
                            ),
                        )
                    )
            }
    }

private fun containment(recursive: Boolean): QueryContainmentDocument =
    if (recursive) QueryContainmentDocument.DESCENDANTS else QueryContainmentDocument.DIRECT

internal fun PublicToolDeclarationKinds.lowerDiscoveryKind(): QueryDeclarationKindDocument =
    when (this) {
        PublicToolDeclarationKinds.CLASS -> QueryDeclarationKindDocument.CLASS
        PublicToolDeclarationKinds.FUNCTION -> QueryDeclarationKindDocument.FUNCTION
        PublicToolDeclarationKinds.PROPERTY -> QueryDeclarationKindDocument.PROPERTY
        PublicToolDeclarationKinds.TYPE_ALIAS -> QueryDeclarationKindDocument.TYPE_ALIAS
    }

private fun rejected(parameter: PublicToolParameter, rule: PublicToolRule) =
    Refinement.Rejected(PublicToolInputFailure.Parameter(parameter, rule))

private fun <T> bounded(values: List<T>): BoundedProtocolList<T> = proven(BoundedProtocolList.create(values))
