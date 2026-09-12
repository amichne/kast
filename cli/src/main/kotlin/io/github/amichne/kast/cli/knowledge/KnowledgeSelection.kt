package io.github.amichne.kast.cli.knowledge

/** Bounded installed-knowledge selector admitted at the CLI argument boundary. */
@JvmInline
value class KnowledgeSelection private constructor(val value: String) {
    companion object {
        fun parse(raw: String): KnowledgeSelectionAdmission {
            val value = raw.trim()
            return when {
                value.isEmpty() -> KnowledgeSelectionAdmission.Rejected(KnowledgeSelectionFailure.EMPTY)
                value.encodeToByteArray().size > MAX_KNOWLEDGE_SELECTION_BYTES ->
                    KnowledgeSelectionAdmission.Rejected(KnowledgeSelectionFailure.TOO_LARGE)
                value.any { it.isISOControl() } ->
                    KnowledgeSelectionAdmission.Rejected(KnowledgeSelectionFailure.CONTROL_CHARACTER)
                else -> KnowledgeSelectionAdmission.Accepted(KnowledgeSelection(value))
            }
        }
    }
}

sealed interface KnowledgeSelectionAdmission {
    data class Accepted(val selection: KnowledgeSelection) : KnowledgeSelectionAdmission

    data class Rejected(val failure: KnowledgeSelectionFailure) : KnowledgeSelectionAdmission
}

enum class KnowledgeSelectionFailure {
    EMPTY,
    TOO_LARGE,
    CONTROL_CHARACTER,
}
