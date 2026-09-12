package io.github.amichne.kast.cli.knowledge

import kotlinx.serialization.Serializable

@Serializable
internal data class KnowledgeManifestDocument(
    val schemaVersion: Int,
    val productVersion: String,
    val sourceRevision: String,
    val declarationEvidence: String,
    val declarationLimitations: List<String>,
    val modules: List<KnowledgeModuleDescriptor>,
)

@Serializable internal data class KnowledgeModuleDescriptor(val projectPath: String, val resource: String)

@Serializable
internal data class KnowledgeModuleDocument(
    val schemaVersion: Int,
    val projectPath: String,
    val moduleDirectory: String,
    val governingGuides: List<KnowledgeGuideReference>,
    val declarations: List<KnowledgeDeclarationDescriptor>,
)

@Serializable
internal data class KnowledgeGuideReference(
    val path: String,
    val sha256: String,
    val resource: String,
)

@Serializable
internal data class KnowledgeDeclarationDescriptor(
    val id: String,
    val declarationPath: String,
    val name: String,
    val kind: String,
    val summary: String,
    val resource: String,
)

@Serializable
internal data class KnowledgeDeclarationDocument(
    val schemaVersion: Int,
    val id: String,
    val projectPath: String,
    val sourcePath: String,
    val declarationPath: String,
    val kind: String,
    val name: String,
    val signature: String,
    val documentation: String,
    val governingGuides: List<String>,
)

@Serializable
internal data class KnowledgeGuideDocument(
    val schemaVersion: Int,
    val path: String,
    val scopeDirectory: String,
    val sha256: String,
    val content: String,
)
