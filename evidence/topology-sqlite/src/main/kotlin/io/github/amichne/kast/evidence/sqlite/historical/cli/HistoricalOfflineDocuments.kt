package io.github.amichne.kast.evidence.sqlite.historical.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val historicalJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
internal enum class OfflineFailureCode {
    INVALID_ARGUMENTS,
    INVALID_REQUEST,
    PAGE_SIZE_OUTSIDE_LIMIT,
    PATH_REJECTED,
    SCHEMA_REJECTED,
    PROFILE_REJECTED,
    CONTENT_REJECTED,
    STORAGE_UNAVAILABLE,
    CAPACITY_EXCEEDED,
    SNAPSHOT_MISMATCH,
    QUERY_INVENTORY_MISMATCH,
    DECLARATION_NOT_FOUND,
    CURSOR_MISMATCH,
    EMPTY_SOURCE_DOMAIN,
    EMPTY_TARGET_DOMAIN,
    SOURCE_OUTSIDE_CAPTURE,
    REFERENCES_NOT_CAPTURED,
    SOURCE_DOMAIN_LIMIT,
    TARGET_DOMAIN_LIMIT,
    TARGET_SELECTION_MISMATCH,
    ATOMIC_PUBLICATION_UNAVAILABLE,
}

@Serializable
internal enum class OfflineCoverageStatus {
    COMPLETE,
    QUALIFIED,
    NOT_COLLECTED,
    OUTSIDE_PROFILE,
}

@Serializable
internal enum class OfflineProfile {
    STRUCTURE_V1,
    REFERENCES_V1,
}

@Serializable
internal enum class OfflineFamily {
    MODULES,
    FILES,
    DECLARATIONS,
    REFERENCES,
}

@Serializable
internal enum class OfflineLimitation {
    UNSUPPORTED_DECLARATION,
    UNRESOLVED_TARGET,
    EXTERNAL_TARGET,
    UNSUPPORTED_TARGET,
    OWNER_UNAVAILABLE,
    MODEL_INCOMPLETE,
    ROOTLESS,
    GENERATED_INPUT,
    RESOURCE_LIMIT,
}

@Serializable
internal data class ManifestDocument(
    val snapshot: String,
    val repository: String,
    val commit: String,
    val tree: String,
    val model: String,
    val resolution: String,
    val compiler: String,
    val producer: String,
    val profile: OfflineProfile,
    val importedModuleCount: Int,
    val familyCoverage: List<FamilyCoverageDocument>,
    val coordinateConvention: CoordinateConvention = CoordinateConvention.UTF16_EXCLUSIVE_END,
)

@Serializable
internal data class FamilyCoverageDocument(
    val family: OfflineFamily,
    val complete: Int,
    val qualified: Int,
    val notCollected: Int,
    val outsideProfile: Int,
    val causes: List<OfflineLimitation>,
)

@Serializable
internal enum class CoordinateConvention {
    UTF16_EXCLUSIVE_END
}

@Serializable
internal data class CoverageDocument(
    val module: String,
    val family: OfflineFamily,
    val status: OfflineCoverageStatus,
    val causes: List<OfflineLimitation>,
)

@Serializable
internal data class ModuleDocument(val id: String, val project: String, val name: String, val sourceSet: String?)

@Serializable
internal data class DeclarationIdentityDocument(val compiler: String, val file: String, val start: Int, val end: Int)

@Serializable
internal data class DeclarationDocument(
    val identity: DeclarationIdentityDocument,
    val name: String,
    val signature: String,
    val kind: DeclarationKindDocument,
)

@Serializable
internal enum class DeclarationKindDocument {
    CLASSLIKE,
    CONSTRUCTOR,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

@Serializable
internal data class FileDocument(
    val path: String,
    val content: String,
    val module: String,
    val provenance: ProvenanceDocument,
)

@Serializable
internal sealed interface ProvenanceDocument {
    @Serializable @SerialName("tracked") data class Tracked(val blob: String) : ProvenanceDocument

    @Serializable @SerialName("generated") data class Generated(val producer: String) : ProvenanceDocument
}

@Serializable
internal enum class ReferenceContextDocument {
    IMPORT,
    ALIASED_IMPORT,
    TYPE,
    CODE,
    FILE_ANNOTATION,
}

@Serializable
internal enum class ReferenceKindDocument {
    REFERENCE,
    CALL,
    TYPE_USE,
    IMPORT,
    INHERITANCE,
    OVERRIDE,
}

@Serializable
internal enum class OwnershipUnavailableDocument {
    UNSUPPORTED_DECLARATION,
    UNRESOLVED_DECLARATION,
}

@Serializable
internal sealed interface OwnerDocument {
    @Serializable
    @SerialName("declaration")
    data class Declaration(val identity: DeclarationIdentityDocument) : OwnerDocument

    @Serializable @SerialName("file") data class File(val context: ReferenceContextDocument) : OwnerDocument

    @Serializable
    @SerialName("unavailable")
    data class Unavailable(val cause: OwnershipUnavailableDocument) : OwnerDocument
}

@Serializable
internal data class UsageRowDocument(
    val ordinal: Long,
    val sourceModule: String,
    val targetModule: String,
    val sourceFile: String,
    val start: Int,
    val end: Int,
    val target: DeclarationIdentityDocument,
    val owner: OwnerDocument,
    val relation: ReferenceKindDocument,
    val context: ReferenceContextDocument,
)

@Serializable
internal data class ReadWorkDocument(
    val artifactRowsExamined: Long,
    val artifactBytes: Long,
    val requestSelectionRowsExamined: Long,
    val pageRowsExamined: Long,
    val returnedRows: Long,
)

@Serializable internal data class RowCursorDocument(val snapshot: String, val family: OfflineFamily, val after: Long)

@Serializable internal data class UsageCursorDocument(val snapshot: String, val query: String, val after: Long)

@Serializable
internal sealed interface OfflineResponse {
    @Serializable @SerialName("rejected") data class Rejected(val reason: OfflineFailureCode) : OfflineResponse

    @Serializable
    @SerialName("inventory")
    data class Inventory(
        val manifest: ManifestDocument,
        val modules: List<ModuleDocument>,
        val coverage: List<CoverageDocument>,
        val fileCount: Long,
        val declarationCount: Long,
        val occurrenceCount: Long,
        val next: RowCursorDocument?,
        val work: ReadWorkDocument,
    ) : OfflineResponse

    @Serializable
    @SerialName("files")
    data class Files(
        val manifest: ManifestDocument,
        val files: List<FileDocument>,
        val next: RowCursorDocument?,
        val work: ReadWorkDocument,
    ) : OfflineResponse

    @Serializable
    @SerialName("declarations")
    data class Declarations(
        val manifest: ManifestDocument,
        val declarations: List<DeclarationDocument>,
        val next: RowCursorDocument?,
        val work: ReadWorkDocument,
    ) : OfflineResponse

    @Serializable
    @SerialName("usage")
    data class Usage(
        val manifest: ManifestDocument,
        val sourceModules: List<String>,
        val targets: List<DeclarationIdentityDocument>,
        val family: OfflineFamily,
        val coverage: OfflineCoverageStatus,
        val qualifications: List<CoverageDocument>,
        val occurrences: List<UsageRowDocument>,
        val occurrenceCount: Int,
        val pageStart: UsageCursorDocument?,
        val next: UsageCursorDocument?,
        val work: ReadWorkDocument,
    ) : OfflineResponse
}
