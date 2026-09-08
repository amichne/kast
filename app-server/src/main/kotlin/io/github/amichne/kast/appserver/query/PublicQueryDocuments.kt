// Generated from query.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class PublicQueryMatch {
    EXACT,
    FUZZY,
}

@Serializable
internal enum class PublicQueryDeclarationKind {
    CLASS,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

@Serializable
internal enum class PublicQueryContainment {
    DIRECT,
    RECURSIVE,
}

@Serializable
internal enum class PublicQueryVisibility {
    PUBLIC,
    PROTECTED,
    INTERNAL,
    PRIVATE,
    LOCAL,
}

@Serializable
internal enum class PublicQueryRelation {
    REFERENCES,
    CALLERS,
    CALLEES,
    IMPLEMENTATIONS,
    INHERITORS,
    OVERRIDES,
    TYPE_USES,
}

@Serializable
internal enum class PublicQueryField {
    NAME,
    LOCATION,
    SIGNATURE,
}

@Serializable
internal enum class PublicQueryDocumentType {
    QUERY,
}

@Serializable
internal enum class PublicQueryScopeType {
    DIRECTORY,
    PACKAGE,
}

@Serializable
internal sealed interface PublicQuerySource

@Serializable
internal sealed interface PublicQueryStep

@Serializable
internal data class PublicQueryDocument(
    val type: PublicQueryDocumentType,
    val from: PublicQuerySource,
    val steps: BoundedProtocolList<PublicQueryStep> = queryListOf(),
    val select: BoundedProtocolList<PublicQueryField> = queryListOf(PublicQueryField.NAME, PublicQueryField.LOCATION),
)

@Serializable(with = PublicQueryScopeSerializer::class)
internal sealed interface PublicQueryScope {
    val containment: PublicQueryContainment
    val sourceSets: BoundedProtocolList<ProtocolText>

    data class Directory(
        val value: WorkspaceRelativePath,
        override val containment: PublicQueryContainment = PublicQueryContainment.RECURSIVE,
        override val sourceSets: BoundedProtocolList<ProtocolText> = queryListOf(queryValue(ProtocolText.parse("main")), queryValue(ProtocolText.parse("test"))),
    ) : PublicQueryScope

    data class Package(
        val value: PublicQueryPackageName,
        override val containment: PublicQueryContainment = PublicQueryContainment.RECURSIVE,
        override val sourceSets: BoundedProtocolList<ProtocolText> = queryListOf(queryValue(ProtocolText.parse("main")), queryValue(ProtocolText.parse("test"))),
    ) : PublicQueryScope
}

@Serializable
private data class PublicQueryScopeEnvelope(
    val type: PublicQueryScopeType,
    val value: ProtocolText,
    val containment: PublicQueryContainment = PublicQueryContainment.RECURSIVE,
    val sourceSets: BoundedProtocolList<ProtocolText> = queryListOf(queryValue(ProtocolText.parse("main")), queryValue(ProtocolText.parse("test"))),
)

internal object PublicQueryScopeSerializer : KSerializer<PublicQueryScope> {
    override val descriptor: SerialDescriptor = PublicQueryScopeEnvelope.serializer().descriptor

    override fun deserialize(decoder: Decoder): PublicQueryScope {
        val input = PublicQueryScopeEnvelope.serializer().deserialize(decoder)
        return when (input.type) {
            PublicQueryScopeType.DIRECTORY -> PublicQueryScope.Directory(
                value = queryValue(WorkspaceRelativePath.parse(input.value.value)),
                containment = input.containment,
                sourceSets = input.sourceSets,
            )
            PublicQueryScopeType.PACKAGE -> PublicQueryScope.Package(
                value = queryValue(PublicQueryPackageName.parse(input.value.value)),
                containment = input.containment,
                sourceSets = input.sourceSets,
            )
        }
    }

    override fun serialize(encoder: Encoder, value: PublicQueryScope) {
        val output = when (value) {
            is PublicQueryScope.Directory -> PublicQueryScopeEnvelope(
                type = PublicQueryScopeType.DIRECTORY,
                value = queryValue(ProtocolText.parse(value.value.value)),
                containment = value.containment,
                sourceSets = value.sourceSets,
            )
            is PublicQueryScope.Package -> PublicQueryScopeEnvelope(
                type = PublicQueryScopeType.PACKAGE,
                value = queryValue(ProtocolText.parse(value.value.value)),
                containment = value.containment,
                sourceSets = value.sourceSets,
            )
        }
        PublicQueryScopeEnvelope.serializer().serialize(encoder, output)
    }
}

@Serializable
@SerialName("SEARCH")
internal data class PublicQuerySearch(
    val query: ProtocolText,
    val match: PublicQueryMatch = PublicQueryMatch.EXACT,
    val kinds: BoundedProtocolList<PublicQueryDeclarationKind> = queryListOf(PublicQueryDeclarationKind.CLASS, PublicQueryDeclarationKind.FUNCTION, PublicQueryDeclarationKind.PROPERTY, PublicQueryDeclarationKind.TYPE_ALIAS),
    val scope: PublicQueryScope = PublicQueryScope.Directory(value = queryValue(WorkspaceRelativePath.parse("."))),
) : PublicQuerySource

@Serializable
@SerialName("ALL")
internal data class PublicQueryAll(
    val kinds: BoundedProtocolList<PublicQueryDeclarationKind> = queryListOf(PublicQueryDeclarationKind.CLASS, PublicQueryDeclarationKind.FUNCTION, PublicQueryDeclarationKind.PROPERTY, PublicQueryDeclarationKind.TYPE_ALIAS),
    val scope: PublicQueryScope = PublicQueryScope.Directory(value = queryValue(WorkspaceRelativePath.parse("."))),
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

private fun <T> queryListOf(vararg values: T): BoundedProtocolList<T> =
    queryValue(BoundedProtocolList.create(values.toList()))

/** Refinement failures become the serialization boundary's expected rejection protocol. */
private fun <T> queryValue(result: Refinement<T, *>): T = when (result) {
    is Refinement.Refined -> result.value
    is Refinement.Rejected -> throw SerializationException("Invalid public query value: ${result.failure}")
}
