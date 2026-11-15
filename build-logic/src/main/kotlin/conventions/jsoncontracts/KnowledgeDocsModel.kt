package conventions.jsoncontracts

import kotlinx.serialization.Serializable

@Serializable
enum class KnowledgeDocsFailureCode { INVALID_KOTLIN, UNSUPPORTED_SOURCE, UNREADABLE_SOURCE, NON_CANONICAL_SOURCE }
