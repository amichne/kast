// Generated from query.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class PublicQueryMatch {
    @SerialName("exact")
    EXACT,
    @SerialName("fuzzy")
    FUZZY,
}

@Serializable
internal enum class PublicQueryDeclarationKind {
    @SerialName("class")
    CLASS,
    @SerialName("function")
    FUNCTION,
    @SerialName("property")
    PROPERTY,
    @SerialName("type-alias")
    TYPE_ALIAS,
}

@Serializable
internal enum class PublicQueryContainment {
    @SerialName("direct")
    DIRECT,
    @SerialName("recursive")
    RECURSIVE,
}

@Serializable
internal enum class PublicQueryVisibility {
    @SerialName("public")
    PUBLIC,
    @SerialName("protected")
    PROTECTED,
    @SerialName("internal")
    INTERNAL,
    @SerialName("private")
    PRIVATE,
    @SerialName("local")
    LOCAL,
}

@Serializable
internal enum class PublicQueryRelation {
    @SerialName("references")
    REFERENCES,
    @SerialName("callers")
    CALLERS,
    @SerialName("callees")
    CALLEES,
    @SerialName("implementations")
    IMPLEMENTATIONS,
    @SerialName("inheritors")
    INHERITORS,
    @SerialName("overrides")
    OVERRIDES,
    @SerialName("type-uses")
    TYPE_USES,
}

@Serializable
internal enum class PublicQueryField {
    @SerialName("name")
    NAME,
    @SerialName("location")
    LOCATION,
    @SerialName("signature")
    SIGNATURE,
}

@Serializable
internal enum class PublicQueryDocumentType {
    @SerialName("QUERY")
    QUERY,
}

@Serializable
internal enum class PublicQueryDirectoryType {
    @SerialName("DIRECTORY")
    DIRECTORY,
}

@Serializable
internal enum class PublicQueryPackageType {
    @SerialName("PACKAGE")
    PACKAGE,
}

@Serializable
internal enum class PublicQueryScopeType {
    @SerialName("SCOPE")
    SCOPE,
}

@Serializable
internal sealed interface PublicQuerySource

@Serializable
internal sealed interface PublicQueryStep

@Serializable
internal data class PublicQueryDocument(
    val type: PublicQueryDocumentType,
    val from: PublicQuerySource,
    val steps: BoundedProtocolList<PublicQueryStep>? = null,
    val select: BoundedProtocolList<PublicQueryField>? = null,
)

@Serializable
internal data class PublicQueryDirectory(
    val type: PublicQueryDirectoryType,
    val path: ProtocolText,
    val containment: PublicQueryContainment? = null,
)

@Serializable
internal data class PublicQueryPackage(
    val type: PublicQueryPackageType,
    val name: ProtocolText,
    val containment: PublicQueryContainment? = null,
)

@Serializable
internal data class PublicQueryScope(
    val type: PublicQueryScopeType,
    val sourceSets: BoundedProtocolList<ProtocolText>? = null,
    val directory: PublicQueryDirectory? = null,
    val `package`: PublicQueryPackage? = null,
)

@Serializable
@SerialName("SEARCH")
internal data class PublicQuerySearch(
    val query: ProtocolText,
    val match: PublicQueryMatch? = null,
    val kinds: BoundedProtocolList<PublicQueryDeclarationKind>? = null,
    val scope: PublicQueryScope? = null,
) : PublicQuerySource

@Serializable
@SerialName("ALL")
internal data class PublicQueryAll(
    val kinds: BoundedProtocolList<PublicQueryDeclarationKind>? = null,
    val scope: PublicQueryScope? = null,
) : PublicQuerySource

@Serializable
@SerialName("REFS")
internal data class PublicQueryRefs(
    val refs: BoundedProtocolList<ProtocolText>,
) : PublicQuerySource

@Serializable
@SerialName("FILTER")
internal data class PublicQueryFilter(
    val visibility: BoundedProtocolList<PublicQueryVisibility>,
) : PublicQueryStep

@Serializable
@SerialName("EXPAND")
internal data class PublicQueryExpand(
    val relation: PublicQueryRelation,
) : PublicQueryStep

@Serializable
@SerialName("DISTINCT")
internal data object PublicQueryDistinct : PublicQueryStep
