// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("DIRECTORY")
internal data class PublicToolDirectoryScope(
    val relativeDirectoryPath: ProtocolText,
    val includeSubdirectories: Boolean? = null,
    val sourceSetNames: BoundedProtocolList<ProtocolText>? = null,
) : PublicToolScope

@Serializable
@SerialName("PACKAGE")
internal data class PublicToolPackageScope(
    val packageName: ProtocolText,
    val includeSubpackages: Boolean? = null,
    val sourceSetNames: BoundedProtocolList<ProtocolText>? = null,
) : PublicToolScope

@Serializable
@SerialName("SEARCH_DECLARATIONS")
internal data class PublicToolSearchSource(
    val declarationName: ProtocolText,
    val nameMatch: PublicToolNameMatch? = null,
    val declarationKinds: BoundedProtocolList<PublicToolDeclarationKinds>? = null,
    val scope: PublicToolScope? = null,
) : PublicToolSource

@Serializable
@SerialName("ALL_DECLARATIONS")
internal data class PublicToolAllSource(
    val declarationKinds: BoundedProtocolList<PublicToolDeclarationKinds>? = null,
    val scope: PublicToolScope? = null,
) : PublicToolSource

@Serializable
@SerialName("AT_LOCATION")
internal data class PublicToolLocationSource(
    val file: ProtocolText,
    val offset: Int,
) : PublicToolSource

@Serializable
@SerialName("SEARCH_TEXT")
internal data class PublicToolTextSource(
    val word: ProtocolText,
    val declarationKinds: BoundedProtocolList<PublicToolDeclarationKinds>? = null,
    val scope: PublicToolScope? = null,
) : PublicToolSource
