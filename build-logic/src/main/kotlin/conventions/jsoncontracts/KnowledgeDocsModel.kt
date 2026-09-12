package conventions.jsoncontracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

@Serializable
data class KnowledgeDocsRequest(
    val repositoryRoot: String,
    val sources: List<String>,
    val output: String,
)

@Serializable
data class KnowledgeDeclarationDocument(
    val sourcePath: String,
    val declarationPath: String,
    val kind: KnowledgeDeclarationKind,
    val name: String,
    val signature: String,
    val documentation: String,
)

@Serializable
data class KnowledgeDocsFailure(
    val sourcePath: String,
    val reason: KnowledgeDocsFailureCode,
)

@Serializable
sealed interface KnowledgeDocsDocument {
    @Serializable
    @SerialName("complete")
    data class Complete(
        val schemaVersion: Int = 1,
        val evidence: KnowledgeDeclarationEvidence = KnowledgeDeclarationEvidence.KOTLIN_PSI_SYNTAX,
        val declarations: List<KnowledgeDeclarationDocument>,
    ) : KnowledgeDocsDocument

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val schemaVersion: Int = 1,
        val failures: List<KnowledgeDocsFailure>,
    ) : KnowledgeDocsDocument
}

@Serializable
enum class KnowledgeDeclarationKind {
    @SerialName("class") CLASS,
    @SerialName("interface") INTERFACE,
    @SerialName("enum") ENUM,
    @SerialName("annotation") ANNOTATION,
    @SerialName("object") OBJECT,
    @SerialName("enum-entry") ENUM_ENTRY,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("variable") VARIABLE,
    @SerialName("typealias") TYPEALIAS,
}

@Serializable
enum class KnowledgeDeclarationEvidence { KOTLIN_PSI_SYNTAX }

@Serializable
enum class KnowledgeDeclarationLimitation {
    KOTLIN_SOURCE_ONLY, NAMED_DECLARATIONS_ONLY, NO_TYPE_RESOLUTION, NO_INHERITED_DOCUMENTATION
}

@Serializable
enum class KnowledgeDocsFailureCode { INVALID_KOTLIN, UNSUPPORTED_SOURCE, UNREADABLE_SOURCE, NON_CANONICAL_SOURCE }

val knowledgeDocsJson = Json { classDiscriminator = "status"; encodeDefaults = true; explicitNulls = true }
