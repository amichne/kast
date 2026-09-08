package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.AgentToolDefinition
import io.github.amichne.kast.protocol.registry.AgentToolName
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions

internal const val APP_SERVER_ENABLE_ENVIRONMENT = "KAST_ENABLE_APP_SERVER"
internal const val APP_SERVER_TOOLS_ENVIRONMENT = "KAST_APP_SERVER_TOOLS"

/** Whether Kast is authorized to own Codex's local App Server endpoint. */
internal enum class AppServerToolingMode {
    ENABLED,
    DISABLED,
    ;

    companion object {
        /** Proof transition: `KAST_ENABLE_APP_SERVER? -> AppServerToolingMode`. */
        internal fun admit(raw: String?): Refinement<AppServerToolingMode, AppServerToolingModeFailure> =
            when (raw) {
                null, "1" -> Refinement.Refined(ENABLED)
                "0" -> Refinement.Refined(DISABLED)
                else -> Refinement.Rejected(AppServerToolingModeFailure.UNKNOWN_VALUE)
            }
    }
}

internal enum class AppServerToolingModeFailure { UNKNOWN_VALUE }

/** Exact non-empty subset of the installed hosted catalog authorized for one broker identity. */
internal class KastToolSelection private constructor(
    private val names: Set<AgentToolName>,
) {
    internal val environmentValue: String = CanonicalAgentToolDefinitions.all
        .filter(::admits)
        .joinToString(",") { definition -> definition.name.value }

    internal fun admits(definition: AgentToolDefinition): Boolean = definition.name in names

    companion object {
        /**
         * Proof transition: `KAST_APP_SERVER_TOOLS? -> KastToolSelection`.
         *
         * Absence selects the canonical default catalog. Present values must name a non-empty,
         * duplicate-free subset of exact installed tool names. The canonical catalog order is
         * retained for identity and publication; raw configuration is not interpreted downstream.
         */
        internal fun admit(raw: String?): Refinement<KastToolSelection, KastToolSelectionFailure> {
            if (raw == null) return Refinement.Refined(defaults())
            if (raw.isBlank()) return Refinement.Rejected(KastToolSelectionFailure.EMPTY)
            val tokens = raw.split(',')
            if (tokens.any(String::isBlank)) {
                return Refinement.Rejected(KastToolSelectionFailure.EMPTY_NAME)
            }
            if (tokens.toSet().size != tokens.size) {
                return Refinement.Rejected(KastToolSelectionFailure.DUPLICATE_NAME)
            }
            val definitionsByName = CanonicalAgentToolDefinitions.all.associateBy { it.name.value }
            val admitted = tokens.map { token ->
                definitionsByName[token]?.name
                    ?: return Refinement.Rejected(KastToolSelectionFailure.UNKNOWN_NAME)
            }
            return Refinement.Refined(KastToolSelection(admitted.toSet()))
        }

        internal fun defaults(): KastToolSelection = KastToolSelection(
            CanonicalAgentToolDefinitions.defaultAppServerTools.mapTo(linkedSetOf()) { definition ->
                definition.name
            },
        )
    }
}

internal enum class KastToolSelectionFailure {
    EMPTY,
    EMPTY_NAME,
    DUPLICATE_NAME,
    UNKNOWN_NAME,
}
