package support.knowledge

import conventions.jsoncontracts.KnowledgeDeclarationKind
import conventions.jsoncontracts.KnowledgeDeclarationEvidence
import conventions.jsoncontracts.KnowledgeDeclarationLimitation

import kotlinx.serialization.Serializable

@Serializable
internal data class InstalledKnowledgeManifest(
    val schemaVersion: Int = 1,
    val productVersion: String,
    val sourceRevision: String,
    val declarationEvidence: KnowledgeDeclarationEvidence,
    val declarationLimitations: List<KnowledgeDeclarationLimitation>,
    val modules: List<InstalledKnowledgeModuleDescriptor>,
    val guides: List<InstalledKnowledgeGuideReference>,
)

@Serializable
internal data class InstalledKnowledgeModuleDescriptor(
    val projectPath: String,
    val resource: String,
)

@Serializable
internal data class InstalledKnowledgeModule(
    val schemaVersion: Int = 1,
    val projectPath: String,
    val moduleDirectory: String,
    val governingGuides: List<InstalledKnowledgeGuideReference>,
    val declarations: List<InstalledKnowledgeDeclarationDescriptor>,
)

@Serializable
internal data class InstalledKnowledgeGuideReference(
    val path: String,
    val sha256: String,
    val resource: String,
)

@Serializable
internal data class InstalledKnowledgeGuide(
    val schemaVersion: Int = 1,
    val path: String,
    val scopeDirectory: String,
    val sha256: String,
    val content: String,
)

@Serializable
internal data class InstalledKnowledgeDeclarationDescriptor(
    val id: String,
    val declarationPath: String,
    val name: String,
    val kind: KnowledgeDeclarationKind,
    val summary: String,
    val resource: String,
)

@Serializable
internal data class InstalledKnowledgeDeclaration(
    val schemaVersion: Int = 1,
    val id: String,
    val projectPath: String,
    val sourcePath: String,
    val declarationPath: String,
    val kind: KnowledgeDeclarationKind,
    val name: String,
    val signature: String,
    val documentation: String,
    val governingGuides: List<String>,
)
