package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QuerySourceSetDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryVisibilityDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument

internal fun PublicQueryMatch.canonical(): SymbolDiscoveryMatchDocument = when (this) {
    PublicQueryMatch.EXACT -> SymbolDiscoveryMatchDocument.EXACT_NAME
    PublicQueryMatch.FUZZY -> SymbolDiscoveryMatchDocument.FUZZY
}

internal fun PublicQueryDeclarationKind.canonical(): QueryDeclarationKindDocument = when (this) {
    PublicQueryDeclarationKind.CLASS -> QueryDeclarationKindDocument.CLASS
    PublicQueryDeclarationKind.FUNCTION -> QueryDeclarationKindDocument.FUNCTION
    PublicQueryDeclarationKind.PROPERTY -> QueryDeclarationKindDocument.PROPERTY
    PublicQueryDeclarationKind.TYPE_ALIAS -> QueryDeclarationKindDocument.TYPE_ALIAS
}

internal fun PublicQuerySourceSet.canonical(): QuerySourceSetDocument = when (this) {
    PublicQuerySourceSet.MAIN -> QuerySourceSetDocument.MAIN
    PublicQuerySourceSet.TEST -> QuerySourceSetDocument.TEST
}

internal fun PublicQueryContainment.canonical(): QueryContainmentDocument = when (this) {
    PublicQueryContainment.DIRECT -> QueryContainmentDocument.DIRECT
    PublicQueryContainment.DESCENDANTS -> QueryContainmentDocument.DESCENDANTS
}

internal fun PublicQueryVisibility.canonical(): QueryVisibilityDocument = when (this) {
    PublicQueryVisibility.PUBLIC -> QueryVisibilityDocument.PUBLIC
    PublicQueryVisibility.PROTECTED -> QueryVisibilityDocument.PROTECTED
    PublicQueryVisibility.INTERNAL -> QueryVisibilityDocument.INTERNAL
    PublicQueryVisibility.PRIVATE -> QueryVisibilityDocument.PRIVATE
    PublicQueryVisibility.LOCAL -> QueryVisibilityDocument.LOCAL
}

internal fun PublicQueryRelation.canonical(): RelationKindDocument = when (this) {
    PublicQueryRelation.REFERENCES -> RelationKindDocument.REFERENCES
    PublicQueryRelation.CALLERS -> RelationKindDocument.CALLERS
    PublicQueryRelation.CALLEES -> RelationKindDocument.CALLEES
    PublicQueryRelation.IMPLEMENTATIONS -> RelationKindDocument.IMPLEMENTATIONS
    PublicQueryRelation.INHERITORS -> RelationKindDocument.INHERITORS
    PublicQueryRelation.OVERRIDES -> RelationKindDocument.OVERRIDES
    PublicQueryRelation.TYPE_USES -> RelationKindDocument.TYPE_USES
}

internal fun PublicQueryField.canonical(): QuerySymbolFieldDocument = when (this) {
    PublicQueryField.NAME -> QuerySymbolFieldDocument.NAME
    PublicQueryField.LOCATION -> QuerySymbolFieldDocument.LOCATION
    PublicQueryField.SIGNATURE -> QuerySymbolFieldDocument.SIGNATURE
}

